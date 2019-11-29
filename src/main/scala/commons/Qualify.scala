package commons

import org.opencv.core.{ CvType, Mat, MatOfPoint, MatOfPoint2f, Point }
import commons.Utils._
import commons.Helpers._
import commons.Constants._
import org.opencv.imgproc.Imgproc
import zio.ZIO

import scala.collection.mutable.ListBuffer
import scala.collection.JavaConverters._
import modules.SkyBlueLogger.logger
import modules.LoggingModule
import models._
import org.opencv.aruco.Aruco
object Qualify {

  def qualifyProcess(corners: java.util.List[Mat],
                     ids: Mat,
                     img: Mat): ZIO[LoggingModule, Exception, Exam] =
    if (!corners.isEmpty && corners.size() == 4) {
      for {
        _ <- LoggingModule.factory.info("qualify process - exam")
        pointsWithCenter = getCornersTuple(ids, corners.asScala.toList)
          .flatMap(markWithCode => {
            Map(markWithCode.id -> getCenterSquare(markWithCode.id, markWithCode.mat))
          })
          .toMap
        codeMat         = getSubMat(pointsWithCenter(CODE_12), pointsWithCenter(CODE_13), img)
        alternativesMap = getSubMat(pointsWithCenter(ANSWER_42), pointsWithCenter(ANSWER_45), img)
        codeContours         <- findContoursOperation(codeMat.thresholdOperation, CODE)
        alternativesContours <- findContoursOperation(alternativesMap.thresholdOperation, ANSWER)
        codeResult         = getCodeOfMatrix(codeContours)
        alternativesResult = getAnswersOfMatrix(alternativesContours)
      } yield Exam(codeResult, alternativesResult)
    } else {
      LoggingModule.factory.info(s"impossible to process this list of corners") *> ZIO.fail(
        new Exception("Error in corners")
      )
    }

  def findContoursOperationProcess(groupLength: Int,
                                   typeSection: String,
                                   contoursList: List[MatOfPoint],
                                   img: Mat): ZIO[LoggingModule, Exception, List[List[Int]]] =
    typeSection match {
      case ANSWER =>
        ZIO.succeed {
          (sortedGroups(contoursList, VERTICALLY)
            .grouped(groupLength)
            .map(
              sortedGroups(_, HORIZONTALLY)
                .map(bubble => evaluateBubble(bubble, img))
            ))
            .toList
        }
      case CODE =>
        ZIO.succeed {
          (sortedGroups(contoursList, HORIZONTALLY)
            .grouped(groupLength)
            .map(
              sortedGroups(_, VERTICALLY)
                .map(bubble => evaluateBubble(bubble, img))
            ))
            .toList
        }
      case _ =>
        LoggingModule.factory.info(s"not match typeSection") *> ZIO.fail(
          new Exception("match error")
        )
    }

  def findContoursOperation(img: Mat,
                            typeSection: String): ZIO[LoggingModule, Exception, List[List[Int]]] =
    for {
      _ <- LoggingModule.factory.info(s"Starting contours operation in $typeSection")
      out      = new Mat(img.rows(), img.cols(), CvType.CV_8UC3)
      contours = ListBuffer(List[MatOfPoint](): _*).asJava
      closed   = img.closeOperation
      _ = Imgproc.findContours(closed,
                               contours,
                               out,
                               Imgproc.RETR_EXTERNAL,
                               Imgproc.CHAIN_APPROX_SIMPLE)
      contoursList = contours.asScala.filter(filterContour).toList
      (quantity, groupDivide) = if (typeSection == ANSWER) (numberContoursOfAnswers, 20)
      else (numberContoursOfCodes, 10)
      result <- if (contoursList.length == quantity)
        findContoursOperationProcess(groupDivide, typeSection, contoursList, closed)
      else
        LoggingModule.factory.info(
          s"[$typeSection] number of contours below limit ${contoursList.length} < $quantity"
        ) *> ZIO
          .fail(new Exception("contours below limit"))
    } yield result

  def qualifyTemplate(img: Mat): ZIO[LoggingModule, Exception, Exam] =
    for {
      _ <- LoggingModule.factory.info(s"Starting ...")
      dictionary = Aruco.getPredefinedDictionary(Aruco.DICT_4X4_50)
      corners    = ListBuffer(List[Mat](): _*).asJava
      ids        = new Mat()
      _          = Aruco.detectMarkers(img, dictionary, corners, ids)
      exam <- qualifyProcess(corners, ids, img)
    } yield exam

  def warpPerspectiveOperation(mat: Mat): ZIO[LoggingModule, Exception, Mat] = {
    val gray      = mat.grayScaleOperation
    val blurred   = gray.gaussianBlurOperation
    val edged     = blurred.cannyOperation
    val dilated   = edged.dilatationOperation
    val edgedCopy = new Mat(edged.rows(), edged.cols(), edged.`type`())
    edged.copyTo(edgedCopy)
    val contours = ListBuffer(List[MatOfPoint](): _*).asJava
    Imgproc.findContours(dilated,
                         contours,
                         edgedCopy,
                         Imgproc.RETR_EXTERNAL,
                         Imgproc.CHAIN_APPROX_SIMPLE)

    for {
      squareFound <- if (!contours.isEmpty)
        ZIO.succeed(findSquare(contours.asScala.sortWith(sortMatOfPoint).toList))
      else
        LoggingModule.factory.info("failed is Empty") *> ZIO.fail(new Exception("contours empty"))
      square <- ZIO.fromOption(squareFound).mapError(_ => new Exception("squareFound is None"))
      pointsSorted = sortPoints(square, new Point(0, 0))
      measures     = getSizeOfQuad(pointsSorted)
      destImage    = new Mat(measures._2.toInt, measures._1.toInt, mat.`type`())
      dst_mat = new MatOfPoint2f(
        new Point(0, 0),
        new Point(destImage.width() - 1, 0),
        new Point(destImage.width() - 1, destImage.height() - 1),
        new Point(0, destImage.height() - 1)
      )
      transform        = Imgproc.getPerspectiveTransform(pointsSorted, dst_mat)
      _                = Imgproc.warpPerspective(gray, destImage, transform, destImage.size())
      destImageDilated = destImage.dilatationOperation
    } yield destImageDilated
  }

}
