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

package boofcv.alg.shapes.polygon;

import boofcv.alg.InputSanityCheck;
import boofcv.alg.distort.DistortImageOps;
import boofcv.alg.filter.binary.Contour;
import boofcv.alg.filter.binary.LinearContourLabelChang2004;
import boofcv.alg.shapes.edge.PolygonEdgeScore;
import boofcv.alg.shapes.polyline.RefinePolyLine;
import boofcv.alg.shapes.polyline.SplitMergeLineFitLoop;
import boofcv.struct.ConnectRule;
import boofcv.struct.distort.PixelTransform_F32;
import boofcv.struct.image.ImageSInt32;
import boofcv.struct.image.ImageSingleBand;
import boofcv.struct.image.ImageUInt8;
import georegression.geometry.UtilPolygons2D_F64;
import georegression.metric.Area2D_F64;
import georegression.struct.point.Point2D_I32;
import georegression.struct.shapes.Polygon2D_F64;
import georegression.struct.shapes.RectangleLength2D_F32;
import org.ddogleg.struct.FastQueue;
import org.ddogleg.struct.GrowQueue_I32;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * Detects convex polygons with the specified number of sides in an image.  Shapes are assumed to be black shapes
 * against a white background, allowing for thresholding to be used.
 * </p>
 *
 * Processing Steps:
 * <ol>
 * <li>First the input a gray scale image and a binarized version of it.</li>
 * <li>The contours of black blobs are found.</li>
 * <li>From the contours polygons are fitted and refined to pixel accuracy.</li>
 * <li>(Optional) Sub-pixel refinement of the polygon's edges and/or corners.</li>
 * </ol>
 *
 * <p>
 * Subpixel refinement is done using the provided {@link RefinePolygonLineToImage} and {@link RefinePolygonCornersToImage}.
 * If straight lines are straight, as is the case with images with lens distortion removes, then the line based
 * refinement will tend to produce better results.  If lens distortion is present then the corner sub-pixel algorithm
 * is more likely to produce better results.
 * </p>
 *
 * <p>
 * NOTE: If both refinement methods are used then corner is applied first followed by line.<br>
 * NOTE: A binary image is not processed as input because the gray-scale image is used in the sub-pixel line/corner
 * refinement.
 * </p>
 *
 * <p>
 * The returned polygons will encompass the entire black polygon.  Here is a simple example in 1D. If all pixels are
 * white, but pixels ranging from 5 to 10, inclusive, then the returned boundaries would be 5.0 to 11.0.  This
 * means that coordinates 5.0 &le; x < 11.0 are all black.  11.0 is included, but note that the entire pixel 11 is white.
 * </p>
 *
 * @author Peter Abeles
 */
public class BinaryPolygonConvexDetector<T extends ImageSingleBand> {

	// dynamically set minimum split fraction used in contour to polygon conversion
	private double minimumSplitFraction;

	// minimum size of a shape's contour as a fraction of the image width
	private double minContourFraction;
	private int minimumContour; // this is image.width*minContourFraction
	private double minimumArea; // computed from minimumContour

	private LinearContourLabelChang2004 contourFinder = new LinearContourLabelChang2004(ConnectRule.FOUR);
	private ImageSInt32 labeled = new ImageSInt32(1,1);

	// finds the initial polygon around a target candidate
	private SplitMergeLineFitLoop fitPolygon;

	// Improve the selection of corner pixels in the contour
	private RefinePolyLine improveContour = new RefinePolyLine(true,20);

	// Refines the estimate of the polygon's lines using a subpixel technique
	private RefinePolygonLineToImage<T> refineLine;
	// Refines the estimate of the polygon's corners using a subpixel technique
	private RefinePolygonCornersToImage<T> refineCorner;

	// List of all squares that it finds
	private FastQueue<Polygon2D_F64> found;

	// type of input image
	private Class<T> inputType;

	// number of lines allowed in the polygon
	private int numberOfSides[];

	// work space for initial polygon
	private Polygon2D_F64 workPoly = new Polygon2D_F64();

	// should the order of the polygon be on clockwise order on output?
	private boolean outputClockwise;

	// storage for the contours associated with a found target.  used for debugging
	private List<Contour> foundContours = new ArrayList<Contour>();

	// transforms which can be used to handle lens distortion
	protected PixelTransform_F32 toUndistorted, toDistorted;

	boolean verbose = false;

	// used to remove false positives
	PolygonEdgeScore differenceScore;
	// should it check the edge score before?  With a chessboard pattern the initial guess is known to be very poor
	// so it should only check the edge after.  Otherwise its good to filter before optimization.
	boolean checkEdgeBefore = true;

	/**
	 * Configures the detector.
	 *
	 * @param polygonSides Number of lines in the polygon
	 * @param contourToPolygon Fits a crude polygon to the shape's binary contour
	 * @param differenceScore Used to remove false positives by computing the difference along the polygon's edges.
	 *                        If null then this test is skipped.
	 * @param refineLine (Optional) Refines the polygon's lines.  Set to null to skip step
	 * @param refineCorner (Optional) Refines the polygon's corners.  Set to null to skip step
	 * @param minContourFraction Size of minimum contour as a fraction of the input image's width.  Try 0.23
	 * @param minimumSplitFraction Minimum number of pixels allowed to split a polygon as a fraction of image width.
	 * @param outputClockwise If true then the order of the output polygons will be in clockwise order
	 * @param inputType Type of input image it's processing
	 */
	public BinaryPolygonConvexDetector(int polygonSides[],
									   SplitMergeLineFitLoop contourToPolygon,
									   PolygonEdgeScore differenceScore,
									   RefinePolygonLineToImage<T> refineLine,
									   RefinePolygonCornersToImage<T> refineCorner,
									   double minContourFraction,
									   double minimumSplitFraction,
									   boolean outputClockwise,
									   Class<T> inputType) {


		this.refineLine = refineLine;
		this.refineCorner = refineCorner;
		this.differenceScore = differenceScore;
		this.numberOfSides = polygonSides;
		this.inputType = inputType;
		this.minContourFraction = minContourFraction;
		this.minimumSplitFraction = minimumSplitFraction;
		this.fitPolygon = contourToPolygon;
		this.outputClockwise = outputClockwise;

		workPoly = new Polygon2D_F64(1);
		found = new FastQueue<Polygon2D_F64>(Polygon2D_F64.class,true);
	}

	/**
	 * <p>Specifies transforms which can be used to change coordinates from distorted to undistorted and the opposite
	 * coordinates.  The undistorted image is never explicitly created.</p>
	 *
	 * <p>
	 * WARNING: The undistorted image must have the same bounds as the distorted input image.  This is because
	 * several of the bounds checks use the image shape.  This are simplified greatly by this assumption.
	 * </p>
	 *
	 * @param width Input image width.  Used in sanity check only.
	 * @param height Input image height.  Used in sanity check only.
	 * @param toUndistorted Transform from undistorted to distorted image.
	 * @param toDistorted Transform from distorted to undistorted image.
	 */
	public void setLensDistortion( int width , int height ,
								   PixelTransform_F32 toUndistorted , PixelTransform_F32 toDistorted ) {

		this.toUndistorted = toUndistorted;
		this.toDistorted = toDistorted;

		// sanity check since I think many people will screw this up.
		RectangleLength2D_F32 rect = DistortImageOps.boundBox_F32(width, height, toUndistorted);
		float x1 = rect.x0 + rect.width;
		float y1 = rect.y0 + rect.height;

		float tol = 1e-4f;
		if( rect.getX() < -tol || rect.getY() < -tol || x1 > width+tol || y1 > height+tol ) {
			throw new IllegalArgumentException("You failed the idiot test! RTFM! The undistorted image "+
					"must be contained by the same bounds as the input distorted image");
		}

		if( refineLine != null ) {
			refineLine.setTransform(toDistorted);
		}

		if( refineCorner != null ) {
			refineCorner.getSnapToEdge().setTransform(toDistorted);
		}

		if( differenceScore != null ) {
			differenceScore.setTransform(toDistorted);
		}
	}

	/**
	 * Examines the undistorted gray scake input image for squares.
	 *
	 * @param gray Input image
	 */
	public void process(T gray, ImageUInt8 binary) {
		InputSanityCheck.checkSameShape(binary, gray);

		fitPolygon.setMinimumSplitPixels(Math.max(1,minimumSplitFraction*gray.width));
		if( labeled.width != gray.width || labeled.height == gray.width )
			configure(gray.width,gray.height);

		found.reset();
		foundContours.clear();

		if( differenceScore != null ) {
			differenceScore.setImage(gray);
		}

		findCandidateShapes(gray, binary);
	}

	/**
	 * Specifies the image's intrinsic parameters and target size
	 *
	 * @param width Width of the input image
	 * @param height Height of the input image
	 */
	private void configure( int width , int height ) {

		// resize storage images
		labeled.reshape(width, height);

		// adjust size based parameters based on image size
		this.minimumContour = (int)(width*minContourFraction);
		this.minimumArea = Math.pow(this.minimumContour /4.0,2);
	}

	/**
	 * Finds blobs in the binary image.  Then looks for blobs that meet size and shape requirements.  See code
	 * below for the requirements.  Those that remain are considered to be target candidates.
	 */
	private void findCandidateShapes( T gray , ImageUInt8 binary ) {
		// find binary blobs
		contourFinder.process(binary, labeled);

		// find blobs where all 4 edges are lines
		FastQueue<Contour> blobs = contourFinder.getContours();
		for (int i = 0; i < blobs.size; i++) {
			Contour c = blobs.get(i);

			if( c.external.size() >= minimumContour) {
				// ignore shapes which touch the image border
				if( touchesBorder(c.external))
					continue;

				// remove lens distortion
				if( toUndistorted != null ) {
					removeDistortionFromContour(c.external);
				}

				fitPolygon.process(c.external);

				GrowQueue_I32 splits = fitPolygon.getSplits();

				// only accept polygons with the expected number of sides
				if (!expectedNumberOfSides(splits)) {
					if( verbose ) System.out.println("rejected number of sides. "+splits.size());
					continue;
				}

				// further improve the selection of corner points
				if( !improveContour.fit(c.external,splits) ) {
					if( verbose ) System.out.println("rejected improve contour");
					continue;
				}

				// convert the format of the initial crude polygon
				workPoly.vertexes.resize(splits.size());
				for (int j = 0; j < splits.size(); j++) {
					Point2D_I32 p = c.external.get( splits.get(j));
					workPoly.get(j).set(p.x,p.y);
				}

				// Functions below only supports convex polygons
				if( !UtilPolygons2D_F64.isConvex(workPoly)) {
					if( verbose ) System.out.println("Rejected not convex");
					continue;
				}

				// make sure it's big enough
				double area = Area2D_F64.polygonConvex(workPoly);

				if( area < minimumArea ) {
					if( verbose ) System.out.println("Rejected area");
					continue;
				}

				// test it again with the full threshold
				if( checkEdgeBefore && differenceScore != null && !differenceScore.validate(workPoly)) {
					if( verbose ) System.out.println("Rejected edge score, after: "+differenceScore.getAverageEdgeIntensity());
					continue;
				}

				Polygon2D_F64 refined = found.grow();
				refined.vertexes.resize(splits.size);

				boolean success;
				if( refineCorner != null ) {
					refineCorner.setImage(gray);
					success = refineCorner.refine(c.external,splits,refined)>=3;
				} else if( refineLine != null ){
					refineLine.setImage(gray);
					success = refineLine.refine(workPoly, refined);
				} else {
					refined.set(workPoly);
					success = true;
				}

				// test it again with the full threshold
				if( !checkEdgeBefore && differenceScore != null && !differenceScore.validate(refined)) {
					if( verbose ) System.out.println("Rejected edge score, after: "+differenceScore.getAverageEdgeIntensity());
					continue;
				}

				if( outputClockwise == refined.isCCW() )
					refined.flip();

				// refine the polygon and add it to the found list
				if( success ) {
					c.id = found.size();
					foundContours.add(c);
				} else {
					found.removeTail();
					if( verbose ) System.out.println("Rejected after refine");
				}
			}
		}
	}

	/**
	 * True if the number of sides found matches what it is looking for
	 */
	private boolean expectedNumberOfSides(GrowQueue_I32 splits) {
		for (int j = 0; j < numberOfSides.length; j++) {
			if( numberOfSides[j] == splits.size() ) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Removes lens distortion from the found contour
	 */
	private void removeDistortionFromContour(List<Point2D_I32> contour) {
		for (int j = 0; j < contour.size(); j++) {
			Point2D_I32 p = contour.get(j);
			toUndistorted.compute(p.x,p.y);
			// round to minimize error
			p.x = Math.round(toUndistorted.distX);
			p.y = Math.round(toUndistorted.distY);
		}
	}


	/**
	 * Checks to see if some part of the contour touches the image border.  Most likely cropped
	 */
	protected final boolean touchesBorder( List<Point2D_I32> contour ) {
		int endX = labeled.width-1;
		int endY = labeled.height-1;

		for (int j = 0; j < contour.size(); j++) {
			Point2D_I32 p = contour.get(j);
			if( p.x == 0 || p.y == 0 || p.x == endX || p.y == endY )
			{
				return true;
			}
		}

		return false;
	}

	public ImageSInt32 getLabeled() {
		return labeled;
	}

	public boolean isOutputClockwise() {
		return outputClockwise;
	}

	public FastQueue<Polygon2D_F64> getFound() {
		return found;
	}

	public List<Contour> getFoundContours(){return foundContours;}

	public Class<T> getInputType() {
		return inputType;
	}

	public void setNumberOfSides(int[] numberOfSides) {
		this.numberOfSides = numberOfSides;
	}

	public int[] getNumberOfSides() {
		return numberOfSides;
	}

	public void setVerbose(boolean verbose) {
		this.verbose = verbose;
	}

	public boolean isCheckEdgeBefore() {
		return checkEdgeBefore;
	}

	/**
	 * If set to true it will prune using polygons using their edge intensity before sub-pixel optimization.
	 * This should only be set to false if the initial edge is known to be off by a bit, like with a chessboard.
	 * @param checkEdgeBefore true for checking before and false for after.
	 */
	public void setCheckEdgeBefore(boolean checkEdgeBefore) {
		this.checkEdgeBefore = checkEdgeBefore;
	}
}
