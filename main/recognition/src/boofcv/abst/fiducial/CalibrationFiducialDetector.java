/*
 * Copyright (c) 2011-2015, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.abst.fiducial;

import boofcv.abst.calib.ConfigChessboard;
import boofcv.abst.calib.ConfigSquareGrid;
import boofcv.abst.calib.PlanarCalibrationDetector;
import boofcv.abst.geo.Estimate1ofPnP;
import boofcv.abst.geo.RefinePnP;
import boofcv.alg.distort.LensDistortionOps;
import boofcv.alg.geo.PerspectiveOps;
import boofcv.core.image.GConvertImage;
import boofcv.factory.calib.FactoryPlanarCalibrationTarget;
import boofcv.factory.geo.FactoryMultiView;
import boofcv.struct.calib.IntrinsicParameters;
import boofcv.struct.distort.PointTransform_F64;
import boofcv.struct.geo.Point2D3D;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSingleBand;
import boofcv.struct.image.ImageType;
import georegression.struct.point.Point2D_F64;
import georegression.struct.se.Se3_F64;
import org.ejml.data.DenseMatrix64F;

import java.util.ArrayList;
import java.util.List;

/**
 * Wrapper which allows a calibration target to be used like a fiducial for pose estimation.  The
 * origin of the coordinate system depends on the default for the calibration target.
 *
 * @author Peter Abeles
 */
public class CalibrationFiducialDetector<T extends ImageSingleBand>
		implements FiducialDetector<T>
{
	// detects the calibration target
	PlanarCalibrationDetector detector;
	// intrinsic calibration matrix
	DenseMatrix64F K = new DenseMatrix64F(3,3);
	// transform to remove lens distortion
	PointTransform_F64 distortToUndistorted;

	// non-linear refinement of pose estimate
	Estimate1ofPnP estimatePnP;
	RefinePnP refinePnP;

	// indicates if a target was detected and the found transform
	boolean targetDetected;
	Se3_F64 initialEstimate = new Se3_F64();
	Se3_F64 targetToCamera = new Se3_F64(); // refined

	// storage for converted input image.  Detector only can process ImageFloat32
	ImageFloat32 converted;

	// Expected type of input image
	ImageType<T> type;

	// Known 3D location of points on calibration grid and current obsrevations
	List<Point2D3D> points2D3D;

	// average of width and height
	double width;

	/**
	 * Configure it to detect chessboard style targets
	 */
	public CalibrationFiducialDetector(ConfigChessboard config,
									   Class<T> imageType) {
		PlanarCalibrationDetector detector = FactoryPlanarCalibrationTarget.detectorChessboard(config);
		double sideWidth = config.numCols*config.squareWidth;
		double sideHeight = config.numRows*config.squareWidth;

		double width = (sideWidth+sideHeight)/2.0;

		init(detector, width, imageType);
	}

	/**
	 * Configure it to detect square-grid style targets
	 */
	public CalibrationFiducialDetector(ConfigSquareGrid config,
									   Class<T> imageType) {
		PlanarCalibrationDetector detector = FactoryPlanarCalibrationTarget.detectorSquareGrid(config);
		int squareCols = config.numCols/2+1;
		int squareRows = config.numRows/2+1;
		double sideWidth = squareCols* config.squareWidth + (squareCols-1)*config.spaceWidth;
		double sideHeight = squareRows*config.squareWidth + (squareRows-1)*config.spaceWidth;

		double width = (sideWidth+sideHeight)/2.0;

		init(detector, width, imageType);
	}

	protected void init(PlanarCalibrationDetector detector, double width, Class<T> imageType) {
		this.detector = detector;
		this.type = ImageType.single(imageType);
		this.converted = new ImageFloat32(1,1);

		this.width = width;

		List<Point2D_F64> layout = detector.getLayout();
		points2D3D = new ArrayList<Point2D3D>();
		for (int i = 0; i < layout.size(); i++) {
			Point2D_F64 p2 = layout.get(i);
			Point2D3D p = new Point2D3D();
			p.location.set(p2.x,p2.y,0);

			points2D3D.add(p);
		}

		this.estimatePnP = FactoryMultiView.computePnPwithEPnP(10, 0.1);
		this.refinePnP = FactoryMultiView.refinePnP(1e-8,100);
	}

	@Override
	public void detect(T input) {

		if( input instanceof ImageFloat32 ) {
			converted = (ImageFloat32)input;
		} else {
			converted.reshape(input.width,input.height);
			GConvertImage.convert(input, converted);
		}

		if( !detector.process(converted) ) {
			targetDetected = false;
			return;
		} else {
			targetDetected = true;
		}

		// convert points into normalized image coord
		List<Point2D_F64> points = detector.getDetectedPoints();
		if( points2D3D.size() != points.size() )
			throw new RuntimeException("BUG! should be same size");

		for (int i = 0; i < points2D3D.size(); i++) {
			Point2D3D p23 = points2D3D.get(i);
			Point2D_F64 pixel = points.get(i);

			distortToUndistorted.compute(pixel.x,pixel.y,p23.observation);
		}

		// estimate using PNP
		if( !(estimatePnP.process(points2D3D,initialEstimate) &&
				refinePnP.fitModel(points2D3D, initialEstimate, targetToCamera)) ) {
			targetDetected = false;
		}
	}

	@Override
	public void setIntrinsic(IntrinsicParameters intrinsic) {
		distortToUndistorted = LensDistortionOps.transformPoint(intrinsic).undistort_F64(true,false);
		PerspectiveOps.calibrationMatrix(intrinsic, K);
	}

	@Override
	public int totalFound() {
		return targetDetected ? 1 : 0;
	}

	@Override
	public void getFiducialToCamera(int which, Se3_F64 fiducialToCamera) {
		if( which == 0 )
			fiducialToCamera.set(targetToCamera);
	}

	@Override
	public int getId( int which ) {
		return 0;
	}

	@Override
	public double getWidth(int which) {
		return width;
	}

	@Override
	public ImageType<T> getInputType() {
		return type;
	}

	public List<Point2D_F64> getCalibrationPoints() {
		return detector.getLayout();
	}
}
