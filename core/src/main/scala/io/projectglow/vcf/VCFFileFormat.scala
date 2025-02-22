/*
 * Copyright 2019 The Glow Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.projectglow.vcf

import java.io.BufferedInputStream

import scala.collection.JavaConverters._

import com.google.common.util.concurrent.Striped
import htsjdk.samtools.ValidationStringency
import htsjdk.samtools.util.{BlockCompressedInputStream, OverlapDetector}
import htsjdk.tribble.readers.{AsciiLineReader, AsciiLineReaderIterator, PositionalBufferedStream}
import htsjdk.variant.variantcontext.VariantContext
import htsjdk.variant.vcf._
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{FileStatus, Path}
import org.apache.hadoop.io.Text
import org.apache.hadoop.io.compress.{CodecPool, CompressionCodecFactory, SplittableCompressionCodec}
import org.apache.hadoop.mapreduce.{Job, TaskAttemptContext}
import org.apache.spark.TaskContext
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.util.CompressionCodecs
import org.apache.spark.sql.catalyst.{InternalRow, ScalaReflection}
import org.apache.spark.sql.execution.datasources._
import org.apache.spark.sql.sources.{DataSourceRegister, Filter}
import org.apache.spark.sql.types._
import org.broadinstitute.hellbender.tools.walkers.genotyper.GenotypeAssignmentMethod
import org.broadinstitute.hellbender.utils.SimpleInterval
import org.broadinstitute.hellbender.utils.variant.GATKVariantContextUtils
import org.seqdoop.hadoop_bam.util.{BGZFEnhancedGzipCodec, DatabricksBGZFOutputStream}

import io.projectglow.common.logging.{HlsMetricDefinitions, HlsTagDefinitions, HlsTagValues, HlsUsageLogging}
import io.projectglow.common.{GlowLogging, VCFRow, WithUtils}
import io.projectglow.sql.util.{ComDatabricksDataSource, HadoopLineIterator, SerializableConfiguration}

class VCFFileFormat extends TextBasedFileFormat with DataSourceRegister with HlsUsageLogging {
  var codecFactory: CompressionCodecFactory = _

  override def shortName(): String = "vcf"

  /**
   * This is very similar to [[TextBasedFileFormat.isSplitable()]], but with additional check
   * for files that may or may not be block gzipped.
   *
   * The check for BGZIP compression is adapted from [[org.seqdoop.hadoop_bam.VCFInputFormat]].
   */
  override def isSplitable(
      sparkSession: SparkSession,
      options: Map[String, String],
      path: Path): Boolean = {
    if (codecFactory == null) {
      codecFactory = new CompressionCodecFactory(
        VCFFileFormat.hadoopConfWithBGZ(sparkSession.sessionState.newHadoopConf())
      )
    }

    codecFactory.getCodec(path) match {
      case null => true
      case _: BGZFEnhancedGzipCodec =>
        VCFFileFormat.isValidBGZ(path, sparkSession.sparkContext.hadoopConfiguration)
      case c => c.isInstanceOf[SplittableCompressionCodec]
    }
  }

  override def inferSchema(
      sparkSession: SparkSession,
      options: Map[String, String],
      files: Seq[FileStatus]): Option[StructType] = {
    Option(SchemaDelegate.makeDelegate(options).schema(sparkSession, files))
  }

  // Current limitation: VCF will only contain the samples listed in the first row written
  override def prepareWrite(
      sparkSession: SparkSession,
      job: Job,
      options: Map[String, String],
      dataSchema: StructType): OutputWriterFactory = {

    options.get(VCFOption.COMPRESSION).foreach { compressionOption =>
      if (codecFactory == null) {
        codecFactory =
          new CompressionCodecFactory(VCFFileFormat.hadoopConfWithBGZ(job.getConfiguration))
      }

      val codec = codecFactory.getCodecByName(compressionOption)
      CompressionCodecs.setCodecConfiguration(job.getConfiguration, codec.getClass.getName)
    }

    // record vcfWrite event in the log along with its compression coded
    val logOptions = Map(
      VCFOption.COMPRESSION -> options.get(VCFOption.COMPRESSION).getOrElse("None")
    )
    recordHlsUsage(
      HlsMetricDefinitions.EVENT_HLS_USAGE,
      Map(
        HlsTagDefinitions.TAG_EVENT_TYPE -> HlsTagValues.EVENT_VCF_WRITE
      ),
      blob = hlsJsonBuilder(logOptions)
    )
    new VCFOutputWriterFactory(options)
  }

  override def buildReader(
      spark: SparkSession,
      dataSchema: StructType,
      partitionSchema: StructType,
      requiredSchema: StructType,
      filters: Seq[Filter],
      options: Map[String, String],
      hadoopConf: Configuration): PartitionedFile => Iterator[InternalRow] = {

    val useFilterParser = options.get(VCFOption.USE_FILTER_PARSER).forall(_.toBoolean)
    val useIndex = options.get(VCFOption.USE_TABIX_INDEX).forall(_.toBoolean)

    // record vcfRead event in the log along with the options

    val logOptions = Map(
      VCFOption.FLATTEN_INFO_FIELDS -> options
        .get(VCFOption.FLATTEN_INFO_FIELDS)
        .forall(_.toBoolean),
      VCFOption.INCLUDE_SAMPLE_IDS -> options.get(VCFOption.INCLUDE_SAMPLE_IDS).forall(_.toBoolean),
      VCFOption.SPLIT_TO_BIALLELIC -> options.get(VCFOption.SPLIT_TO_BIALLELIC).exists(_.toBoolean),
      VCFOption.USE_FILTER_PARSER -> useFilterParser,
      VCFOption.USE_TABIX_INDEX -> useIndex
    )

    recordHlsUsage(
      HlsMetricDefinitions.EVENT_HLS_USAGE,
      Map(
        HlsTagDefinitions.TAG_EVENT_TYPE -> HlsTagValues.EVENT_VCF_READ
      ),
      blob = hlsJsonBuilder(logOptions)
    )

    val serializableConf = new SerializableConfiguration(
      VCFFileFormat.hadoopConfWithBGZ(hadoopConf)
    )

    /* Make a filtered interval by parsing the filters
     Filters are parsed even if useTabixIndex is disabled because the parser can help with
     variant skipping in the VCF iterator if there is no overlap with the filteredInterval,
     improving the performance benefiting from lazy loading of genotypes */

    val filteredSimpleInterval =
      TabixIndexHelper.makeFilteredInterval(filters, useFilterParser, useIndex)

    partitionedFile => {

      // In case of a partitioned file that only contains part of the header, codec.readActualHeader
      // will throw an error for a malformed header. We therefore allow the header reader to read
      // past the boundaries of the partitions; it will throw/return when done reading the header.
      val path = new Path(partitionedFile.filePath)
      val (header, codec) = VCFFileFormat.createVCFCodec(path.toString, serializableConf.value)
      val hadoopFs = path.getFileSystem(serializableConf.value)

      // Get the file offset=(startPos,endPos) that must be read from this partitionedFile.
      // Currently only one offset is generated for each partitionedFile.
      val offset = TabixIndexHelper.filteredVariantBlockRange(
        hadoopFs,
        partitionedFile,
        serializableConf.value,
        !filters.isEmpty,
        useIndex,
        filteredSimpleInterval
      )

      offset match {
        case None =>
          // Filter parser has detected that the filter conditions yield an empty set of results.
          Iterator.empty
        case Some((startPos, endPos)) =>
          // Modify the start and end of reader according to the offset provided by
          // tabixIndexHelper.filteredVariantBlockRange
          val reader = new HadoopLineIterator(
            partitionedFile.filePath,
            startPos,
            (endPos - startPos),
            None,
            serializableConf.value
          )

          Option(TaskContext.get()).foreach { taskContext =>
            taskContext.addTaskCompletionListener[Unit] { _ =>
              reader.close()
            }
          }

          SchemaDelegate
            .makeDelegate(options)
            .toRows(
              header,
              requiredSchema,
              VCFIteratorDelegate.makeDelegate(options, reader, codec, filteredSimpleInterval.get))
      }
    }

  }

}

class ComDatabricksVCFFileFormat extends VCFFileFormat with ComDatabricksDataSource

object VCFFileFormat {

  /**
   * Reads the header of a VCF file to generate an object representing the information in the header
   * and a derived [[VCFCodec]] that can parse the rest of the file.
   *
   * The logic to parse the header is adapted from [[org.seqdoop.hadoop_bam.VCFRecordReader]].
   */
  def createVCFCodec(path: String, conf: Configuration): (VCFHeader, VCFCodec) = {
    val hPath = new Path(path)
    val fs = hPath.getFileSystem(conf)
    WithUtils.withCloseable(fs.open(hPath)) { is =>
      val compressionCodec = new CompressionCodecFactory(conf).getCodec(hPath)
      val wrappedStream = if (compressionCodec != null) {
        val decompressor = CodecPool.getDecompressor(compressionCodec)
        compressionCodec.createInputStream(is, decompressor)
      } else {
        new PositionalBufferedStream(is)
      }

      val vcfCodec = new VCFCodec()
      val reader = new AsciiLineReaderIterator(AsciiLineReader.from(wrappedStream))
      val header = vcfCodec
        .readActualHeader(reader)
      (header.asInstanceOf[VCFHeader], vcfCodec)
    }
  }

  /**
   * Adds BGZF support to an existing Hadoop conf.
   */
  def hadoopConfWithBGZ(conf: Configuration): Configuration = {
    val toReturn = new Configuration(conf)
    val bgzCodecs = Seq(
      "io.projectglow.sql.util.BGZFCodec",
      "org.seqdoop.hadoop_bam.util.BGZFEnhancedGzipCodec"
    )
    val codecs = toReturn
        .get("io.compression.codecs", "")
        .split(",")
        .filter(codec => codec.nonEmpty && !bgzCodecs.contains(codec)) ++ bgzCodecs
    toReturn.set("io.compression.codecs", codecs.mkString(","))
    toReturn
  }

  val idxLock = Striped.lock(100)
  val INDEX_SUFFIX = ".tbi"

  /** Checks whether the file is a valid bgzipped file
   * Used by filteredVariantBlockRange to abandon use of tabix if the file is not bgzipped.
   */
  def isValidBGZ(path: Path, conf: Configuration): Boolean = {
    val fs = path.getFileSystem(conf)
    WithUtils.withCloseable(fs.open(path)) { is =>
      val buffered = new BufferedInputStream(is)
      BlockCompressedInputStream.isValidFile(buffered)
    }
  }
}

case class VariantContextWrapper(vc: VariantContext, splitFromMultiallelic: Boolean)

private object VCFIteratorDelegate {
  def makeDelegate(
      options: Map[String, String],
      lineReader: Iterator[Text],
      codec: VCFCodec,
      filteredSimpleInterval: SimpleInterval): AbstractVCFIterator = {
    if (options.get(VCFOption.SPLIT_TO_BIALLELIC).exists(_.toBoolean)) {
      new SplitVCFIterator(new VCFIterator(lineReader, codec, filteredSimpleInterval))
    } else {
      new VCFIterator(lineReader, codec, filteredSimpleInterval)
    }
  }
}

private[vcf] abstract class AbstractVCFIterator(codec: VCFCodec)
    extends Iterator[VariantContextWrapper] {

  def parseLine(line: String): VariantContext = {
    try {
      codec.decode(line)
    } catch {
      // The HTSJDK does not parse nan, only NaN
      case _: NumberFormatException => codec.decode(line.replaceAll("[nN][aA][nN]", "NaN"))
    }
  }
}

private[vcf] class VCFIterator(
    lineReader: Iterator[Text],
    codec: VCFCodec,
    filteredSimpleInterval: SimpleInterval)
    extends AbstractVCFIterator(codec)
    with GlowLogging {
  // filteredSimpleInterval is the SimpleInterval containing the contig and interval generated by
  // the filter parser to be checked for overlap by overlap detector.

  private var nextVC: VariantContext = _ // nextVC always holds the nextVC to be passed by the
  // iterator.
  // Initialize overlapDetector if needed
  private val overlapDetector: OverlapDetector[SimpleInterval] =
    if (!filteredSimpleInterval
        .getContig
        .isEmpty) {
      OverlapDetector.create(
        scala.collection.immutable.List[SimpleInterval](filteredSimpleInterval).asJava)
    } else {
      null
    }

  // Initialize
  nextVC = findNextVC()

  override def hasNext: Boolean = {
    nextVC != null
  }

  override def next(): VariantContextWrapper = {
    if (nextVC == null) {
      throw new NoSuchElementException("Called next on empty iterator")
    }
    val retVC = nextVC
    nextVC = findNextVC()
    VariantContextWrapper(retVC, false)
  }

  /** Finds next VC that satisfies the filteredInterval. As genotype data is lazily loaded, this
   * dramatically improves the loading when filtering.
   */
  private def findNextVC(): VariantContext = {

    var nextFilteredVC: VariantContext = null

    while (lineReader.hasNext && nextFilteredVC == null) {
      val nextUnfilteredVC = parseLine(lineReader.next.toString)
      if (nextUnfilteredVC != null && overlaps(nextUnfilteredVC)) {
        nextFilteredVC = nextUnfilteredVC
      }
    }
    nextFilteredVC
  }

  private def overlaps(vc: VariantContext): Boolean = {

    if (filteredSimpleInterval.getContig.isEmpty) {
      // Empty contig means that the filter parser is not being used due to multiple contigs
      // being queried or filter parser being disabled by the user.
      true
    } else {
      val vcInterval = new SimpleInterval(vc.getContig, vc.getStart, vc.getEnd)
      overlapDetector.overlapsAny(vcInterval)
    }
  }

  def getCodec: VCFCodec = codec

}

private[vcf] class SplitVCFIterator(baseIterator: VCFIterator)
    extends AbstractVCFIterator(baseIterator.getCodec)
    with GlowLogging {

  private var isSplit: Boolean = false
  private var nextVC: VariantContext = _ // nextVC always holds the nextVC to be passed by the
  // iterator.
  private var nextVCs: Iterator[VariantContext] = Iterator.empty

  // Initialize
  nextVC = findNextBiallelicVC()

  override def hasNext: Boolean = {
    nextVC != null
  }

  override def next(): VariantContextWrapper = {
    if (nextVC == null) {
      throw new NoSuchElementException("Called next on empty iterator")
    }
    val (retVC, retSplit) = (nextVC, isSplit)
    nextVC = findNextBiallelicVC()
    VariantContextWrapper(retVC, retSplit)
  }

  /**
   * Finds next biallelic VC using base Iterator
   */
  private def findNextBiallelicVC(): VariantContext = {

    var nextBiallelicVC: VariantContext = null

    if (nextVCs.hasNext) {
      nextBiallelicVC = nextVCs.next()
    } else if (!baseIterator.hasNext) {
      nextBiallelicVC = null
    } else {
      nextBiallelicVC = baseIterator.next().vc
      val htsjdkVcList =
        GATKVariantContextUtils.splitVariantContextToBiallelics(
          nextBiallelicVC,
          /* trimLeft */ true,
          GenotypeAssignmentMethod.BEST_MATCH_TO_ORIGINAL,
          /* keepOriginalChrCounts */ false
        )
      isSplit = htsjdkVcList.size() > 1
      nextVCs = htsjdkVcList.asScala.toIterator
      nextBiallelicVC = nextVCs.next()
    }
    nextBiallelicVC
  }
}

private[vcf] sealed trait SchemaDelegate {
  def schema(sparkSession: SparkSession, files: Seq[FileStatus]): StructType

  def toRows(
      header: VCFHeader,
      requiredSchema: StructType,
      iterator: Iterator[VariantContextWrapper]): Iterator[InternalRow]
}

/**
 * Abstracts out the parts of VCF parsing that depend on the output schema.
 */
private[vcf] object SchemaDelegate {

  def makeDelegate(options: Map[String, String]): SchemaDelegate = {
    val stringency = VCFOptionParser.getValidationStringency(options)
    val includeSampleId = options.get(VCFOption.INCLUDE_SAMPLE_IDS).forall(_.toBoolean)
    if (options.get(VCFOption.VCF_ROW_SCHEMA).exists(_.toBoolean)) {
      new VCFRowSchemaDelegate(stringency, includeSampleId)
    } else if (options.get(VCFOption.FLATTEN_INFO_FIELDS).forall(_.toBoolean)) {
      new FlattenedInfoDelegate(includeSampleId, stringency)
    } else {
      new NormalDelegate(includeSampleId, stringency)
    }
  }

  private def readHeaders(
      spark: SparkSession,
      files: Seq[FileStatus]): (Seq[VCFInfoHeaderLine], Seq[VCFFormatHeaderLine]) = {
    val serializableConf = new SerializableConfiguration(spark.sessionState.newHadoopConf())

    val filePaths = files.map(_.getPath.toString)
    spark
      .sparkContext
      .parallelize(filePaths)
      .map { path =>
        val (header, _) = VCFFileFormat.createVCFCodec(path, serializableConf.value)
        val infoHeaderLines = header.getInfoHeaderLines.asScala.toSet
        val formatHeaderLines = header.getFormatHeaderLines.asScala.toSet
        (infoHeaderLines, formatHeaderLines)
      }
      .collect()
      .foldLeft((Seq.empty[VCFInfoHeaderLine], Seq.empty[VCFFormatHeaderLine])) {
        case ((allInfo, allFormat), (info, format)) =>
          (allInfo ++ info, allFormat ++ format)
      }
  }

  private class VCFRowSchemaDelegate(stringency: ValidationStringency, includeSampleIds: Boolean)
      extends SchemaDelegate {
    override def schema(sparkSession: SparkSession, files: Seq[FileStatus]): StructType = {
      ScalaReflection.schemaFor[VCFRow].dataType.asInstanceOf[StructType]
    }

    override def toRows(
        header: VCFHeader,
        requiredSchema: StructType,
        iterator: Iterator[VariantContextWrapper]): Iterator[InternalRow] = {
      val converter = new VariantContextToInternalRowConverter(
        header,
        requiredSchema,
        stringency,
        writeSampleIds = includeSampleIds
      )
      iterator.map { vc =>
        converter.convertRow(vc.vc, vc.splitFromMultiallelic)
      }
    }
  }

  private class NormalDelegate(includeSampleIds: Boolean, stringency: ValidationStringency)
      extends SchemaDelegate {
    override def schema(sparkSession: SparkSession, files: Seq[FileStatus]): StructType = {
      val (infoHeaders, formatHeaders) = readHeaders(sparkSession, files)
      VCFSchemaInferrer.inferSchema(includeSampleIds, false, infoHeaders, formatHeaders)
    }

    override def toRows(
        header: VCFHeader,
        requiredSchema: StructType,
        iterator: Iterator[VariantContextWrapper]): Iterator[InternalRow] = {
      val converter = new VariantContextToInternalRowConverter(header, requiredSchema, stringency)
      iterator.map { vc =>
        converter.convertRow(vc.vc, vc.splitFromMultiallelic)
      }
    }
  }

  private class FlattenedInfoDelegate(includeSampleIds: Boolean, stringency: ValidationStringency)
      extends SchemaDelegate {

    override def schema(sparkSession: SparkSession, files: Seq[FileStatus]): StructType = {
      val (infoHeaders, formatHeaders) = readHeaders(sparkSession, files)
      VCFSchemaInferrer.inferSchema(includeSampleIds, true, infoHeaders, formatHeaders)
    }

    override def toRows(
        header: VCFHeader,
        requiredSchema: StructType,
        iterator: Iterator[VariantContextWrapper]): Iterator[InternalRow] = {

      val converter = new VariantContextToInternalRowConverter(header, requiredSchema, stringency)
      iterator.map { vc =>
        converter.convertRow(vc.vc, vc.splitFromMultiallelic)
      }
    }
  }

}

private[projectglow] class VCFOutputWriterFactory(options: Map[String, String])
    extends OutputWriterFactory {

  override def newInstance(
      path: String,
      dataSchema: StructType,
      context: TaskAttemptContext): OutputWriter = {
    val outputStream = CodecStreams.createOutputStream(context, new Path(path))
    DatabricksBGZFOutputStream.setWriteEmptyBlockOnClose(outputStream, true)
    new VCFFileWriter(
      options,
      dataSchema,
      context.getConfiguration,
      outputStream,
      writeHeader = true)
  }

  override def getFileExtension(context: TaskAttemptContext): String = {
    ".vcf" + CodecStreams.getCompressionExtension(context)
  }
}

private[projectglow] object VCFOptionParser {
  def getValidationStringency(options: Map[String, String]): ValidationStringency = {
    val stringency = options.getOrElse(VCFOption.VALIDATION_STRINGENCY, "SILENT").toUpperCase
    ValidationStringency.valueOf(stringency)
  }
}

object VCFOption {
  // Reader-only options
  val FLATTEN_INFO_FIELDS = "flattenInfoFields"
  val INCLUDE_SAMPLE_IDS = "includeSampleIds"
  val SPLIT_TO_BIALLELIC = "splitToBiallelic"
  val VCF_ROW_SCHEMA = "vcfRowSchema"
  val USE_TABIX_INDEX = "useTabixIndex"
  val USE_FILTER_PARSER = "useFilterParser"

  // Writer-only options
  val COMPRESSION = "compression"

  // Reader and writer options
  val VALIDATION_STRINGENCY = "validationStringency"
}
