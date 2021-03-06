/*! ******************************************************************************
*
* Pentaho Data Integration
*
* Copyright (C) 2002-2017 by Hitachi Vantara : http://www.pentaho.com
*
*******************************************************************************
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
******************************************************************************/

package org.pentaho.di.rekognition.steps.face;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.Protocol;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.rekognition.AmazonRekognition;
import com.amazonaws.services.rekognition.AmazonRekognitionClientBuilder;
import com.amazonaws.services.rekognition.model.*;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ListObjectsV2Request;
import com.amazonaws.services.s3.model.ListObjectsV2Result;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.core.exception.KettleStepException;
import org.pentaho.di.core.row.RowDataUtil;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.RowMetaInterface;
import org.pentaho.di.i18n.BaseMessages;
import org.pentaho.di.trans.Trans;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.BaseStep;
import org.pentaho.di.trans.step.StepDataInterface;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMeta;
import org.pentaho.di.trans.step.StepMetaInterface;

import java.util.ArrayList;
import java.util.List;

/**
 * This class is part of the demo step plug-in implementation.
 * It demonstrates the basics of developing a plug-in step for PDI. 
 * 
 * The demo step adds a new string field to the row stream and sets its
 * value to "Hello World!". The user may select the name of the new field.
 *   
 * This class is the implementation of StepInterface.
 * Classes implementing this interface need to:
 * 
 * - initialize the step
 * - execute the row processing logic
 * - dispose of the step 
 * 
 * Please do not create any local fields in a StepInterface class. Store any
 * information related to the processing logic in the supplied step data interface
 * instead.  
 * 
 */

public class FaceAnalysisStep extends BaseStep implements StepInterface {

  private static final Class<?> PKG = FaceAnalysisMeta.class; // for i18n purposes

  /**
   * The constructor should simply pass on its arguments to the parent class.
   * 
   * @param s                 step description
   * @param stepDataInterface step data class
   * @param c                 step copy
   * @param t                 transformation description
   * @param dis               transformation executing
   */
  public FaceAnalysisStep(StepMeta s, StepDataInterface stepDataInterface, int c, TransMeta t, Trans dis ) {
    super( s, stepDataInterface, c, t, dis );
  }

  /**
   * This method is called by PDI during transformation startup. 
   * 
   * It should initialize required for step execution. 
   * 
   * The meta and data implementations passed in can safely be cast
   * to the step's respective implementations. 
   * 
   * It is mandatory that super.init() is called to ensure correct behavior.
   * 
   * Typical tasks executed here are establishing the connection to a database,
   * as wall as obtaining resources, like file handles.
   * 
   * @param smi   step meta interface implementation, containing the step settings
   * @param sdi  step data interface implementation, used to store runtime information
   * 
   * @return true if initialization completed successfully, false if there was an error preventing the step from working. 
   *  
   */
  public boolean init( StepMetaInterface smi, StepDataInterface sdi ) {
    // Casting to step-specific implementation classes is safe
    FaceAnalysisMeta meta = (FaceAnalysisMeta) smi;
    FaceAnalysisData data = (FaceAnalysisData) sdi;
    if ( !super.init( meta, data ) ) {
      return false;
    }

    // Add any step-specific initialization that may be needed here
    // AWS S3 and Rekognition API Initialization (sample code)
    ProfileCredentialsProvider credentialsProvider = new ProfileCredentialsProvider();

    ClientConfiguration clientConfig = new ClientConfiguration();
    clientConfig.setConnectionTimeout(30000);
    clientConfig.setRequestTimeout(60000);
    clientConfig.setProtocol(Protocol.HTTPS);

    data.s3Client = AmazonS3ClientBuilder.standard()
            .withCredentials(credentialsProvider)
            .withRegion("us-east-1")
            .build();

    data.rekognitionClient = AmazonRekognitionClientBuilder
            .standard()
            .withClientConfiguration(clientConfig)
            .withCredentials(credentialsProvider)
            .withRegion("us-east-1")
            .build();

    return true;
  }

  /**
   * Once the transformation starts executing, the processRow() method is called repeatedly
   * by PDI for as long as it returns true. To indicate that a step has finished processing rows
   * this method must call setOutputDone() and return false;
   * 
   * Steps which process incoming rows typically call getRow() to read a single row from the
   * input stream, change or add row content, call putRow() to pass the changed row on 
   * and return true. If getRow() returns null, no more rows are expected to come in, 
   * and the processRow() implementation calls setOutputDone() and returns false to
   * indicate that it is done too.
   * 
   * Steps which generate rows typically construct a new row Object[] using a call to
   * RowDataUtil.allocateRowData(numberOfFields), add row content, and call putRow() to
   * pass the new row on. Above process may happen in a loop to generate multiple rows,
   * at the end of which processRow() would call setOutputDone() and return false;
   * 
   * @param smi the step meta interface containing the step settings
   * @param sdi the step data interface that should be used to store
   * 
   * @return true to indicate that the function should be called again, false if the step is done
   */
  public boolean processRow( StepMetaInterface smi, StepDataInterface sdi ) throws KettleException {

    // safely cast the step settings (meta) and runtime info (data) to specific implementations 
    FaceAnalysisMeta meta = (FaceAnalysisMeta) smi;
    FaceAnalysisData data = (FaceAnalysisData) sdi;

    data.outputRowMeta = new RowMeta();
    // use meta.getFields() to change it, so it reflects the output row structure
    meta.getFields( data.outputRowMeta, getStepname(), null, null, this, null, null );

    // Locate the row index for this step's field
    // If less than 0, the field was not found.
    data.fieldImageFileIndex = data.outputRowMeta.indexOfValue( FaceAnalysisMeta.FIELD_IMAGE_FILE );
    data.fieldFaceIdIndex = data.outputRowMeta.indexOfValue( FaceAnalysisMeta.FIELD_FACE_ID );
    data.fieldPropertyIndex = data.outputRowMeta.indexOfValue( FaceAnalysisMeta.FIELD_PROPERTY );
    data.fieldValueIndex = data.outputRowMeta.indexOfValue( FaceAnalysisMeta.FIELD_VALUE );
    data.fieldConfidenceIndex = data.outputRowMeta.indexOfValue( FaceAnalysisMeta.FIELD_CONFIDENCE );
    if ( data.fieldImageFileIndex < 0 || data.fieldFaceIdIndex < 0 || data.fieldPropertyIndex < 0 ||
         data.fieldValueIndex < 0 || data.fieldConfidenceIndex < 0) {
      log.logError( BaseMessages.getString( PKG, "FaceAnalysisStep.Error.NoOutputField" ) );
      setErrors( 1L );
      setOutputDone();
      return false;
    }


    processAllImages(meta, data);

    setOutputDone();
    return false;
  }

  /**
   * This method is called by PDI once the step is done processing. 
   * 
   * The dispose() method is the counterpart to init() and should release any resources
   * acquired for step execution like file handles or database connections.
   * 
   * The meta and data implementations passed in can safely be cast
   * to the step's respective implementations. 
   * 
   * It is mandatory that super.dispose() is called to ensure correct behavior.
   * 
   * @param smi   step meta interface implementation, containing the step settings
   * @param sdi  step data interface implementation, used to store runtime information
   */
  public void dispose( StepMetaInterface smi, StepDataInterface sdi ) {

    // Casting to step-specific implementation classes is safe
    FaceAnalysisMeta meta = (FaceAnalysisMeta) smi;
    FaceAnalysisData data = (FaceAnalysisData) sdi;

    // Add any step-specific initialization that may be needed here

    // Call superclass dispose()
    super.dispose( meta, data );
  }



  protected void putRowWithFaceProperty(FaceAnalysisData data, String imageFile, String faceId,
                                        String property, String value, Double confidence ) throws KettleStepException {
    Object[] outputRow = RowDataUtil.allocateRowData(data.outputRowMeta.size());
    outputRow[data.fieldImageFileIndex] = imageFile;
    outputRow[data.fieldFaceIdIndex] = faceId;
    outputRow[data.fieldPropertyIndex] = property;
    outputRow[data.fieldValueIndex] = value;
    outputRow[data.fieldConfidenceIndex] = confidence;
    putRow(data.outputRowMeta, outputRow);
  }


  protected void processAllImages(FaceAnalysisMeta meta, FaceAnalysisData data) throws KettleStepException {

    ListObjectsV2Request req = new ListObjectsV2Request().withBucketName(meta.getS3BucketName()); //.withMaxKeys(2);
    ListObjectsV2Result result;

    do {
      result = data.s3Client.listObjectsV2(req);
      for (S3ObjectSummary objectSummary : result.getObjectSummaries()) {
        System.out.printf(" - %s (size: %d)\n", objectSummary.getKey(), objectSummary.getSize());

        DetectFacesRequest request = new DetectFacesRequest()
            .withImage(new Image()
                .withS3Object(new S3Object()
                    .withName(objectSummary.getKey()).withBucket(meta.getS3BucketName())))
            .withAttributes(Attribute.ALL);

        DetectFacesResult facesResult = data.rekognitionClient.detectFaces( request );
        List<FaceDetail> faceDetails = facesResult.getFaceDetails();
        int faceNumber = 1;
        for (FaceDetail faceDetail : faceDetails) {
          processFaceDetails(meta, data, faceDetail, objectSummary.getKey(), faceNumber++);
        }
      }
    } while (result.isTruncated());

  }


  private void processFaceDetails(FaceAnalysisMeta meta, FaceAnalysisData data,
                                  FaceDetail faceDetail, String imageName, int faceNumber) throws KettleStepException {

    //putRowWithFaceProperty(FaceAnalysisData data, String imageFile, String faceId,
    //    String property, String value, Double confidence ) throws KettleStepException {

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_AGERANGELOW", faceDetail.getAgeRange().getLow().toString(), new Double(faceDetail.getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_AGEGANGEHIGH", faceDetail.getAgeRange().getHigh().toString(), new Double(faceDetail.getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_BEARD",faceDetail.getBeard().getValue().toString(), new Double(faceDetail.getBeard().getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_EYEGLASSES",faceDetail.getEyeglasses().getValue().toString(), new Double(faceDetail.getEyeglasses().getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_EYESOPEN",faceDetail.getEyesOpen().getValue().toString(), new Double(faceDetail.getEyesOpen().getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_GENDER",faceDetail.getGender().getValue(), new Double(faceDetail.getGender().getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_SMILE",faceDetail.getSmile().getValue().toString(), new Double(faceDetail.getSmile().getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_MOUTHOPEN",faceDetail.getMouthOpen().getValue().toString(), new Double(faceDetail.getMouthOpen().getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_MUSTACHE",faceDetail.getMustache().getValue().toString(), new Double(faceDetail.getMustache().getConfidence()) );

    putRowWithFaceProperty( data, imageName, ""+faceNumber,
        "DETAIL_SUNGLASSES",faceDetail.getSunglasses().getValue().toString(), new Double(faceDetail.getSunglasses().getConfidence()) );

    // last but not least, add a row per each emotion
    List<Emotion> emotions = faceDetail.getEmotions();
    for (Emotion emotion : emotions) {
      putRowWithFaceProperty( data, imageName, ""+faceNumber,
          "EMOTION", emotion.getType(), new Double(emotion.getConfidence()) );
    }


    /*

    List<Landmark> landmarks = faceDetail.getLandmarks();
    for (Landmark lm : landmarks) {
      System.out.println("Landmark: " + lm.getType()
          + ", x=" + lm.getX() + "; y=" + lm.getY());
    }

    Pose pose = faceDetail.getPose();
    System.out.println("Pose: pitch=" + pose.getPitch() + "; roll=" + pose.getRoll() + "; yaw" + pose.getYaw());

    ImageQuality quality = faceDetail.getQuality();
    System.out.println("Quality: brightness=" + quality.getBrightness() + "; sharpness=" + quality.getSharpness());


    BoundingBox bb = faceDetail.getBoundingBox();
    System.out.println("BoundingBox: left=" + bb.getLeft() +
                ", top=" + bb.getTop() + ", width=" + bb.getWidth() +
                ", height=" + bb.getHeight());

    Float confidence = faceDetail.getConfidence();
    System.out.println("Confidence: " + confidence);

    */
  }
  


}
