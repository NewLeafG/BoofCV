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

package boofcv.app.calib;

import georegression.struct.point.Point2D_F64;

import java.util.List;

/**
 * @author Peter Abeles
 */
public interface CalibrationView {

	void getSides( List<Point2D_F64> detections , List<Point2D_F64> sides );

	double getWidthHeightRatio();

	double getWidthBuffer();

	double getHeightBuffer();

	class Chessboard implements CalibrationView {

		int numRows,numCols;
		int pointRows,pointCols;

		public Chessboard(int numRows, int numCols) {
			this.numRows = numRows;
			this.numCols = numCols;

			pointRows = numRows-1;
			pointCols = numCols-1;
		}

		@Override
		public void getSides(List<Point2D_F64> detections, List<Point2D_F64> sides) {
			sides.add( get(0, 0, detections));
			sides.add( get(0, pointCols-1, detections));

			sides.add( get(0, pointCols-1, detections));
			sides.add( get(pointRows-1, pointCols-1, detections));

			sides.add( get(pointRows-1, pointCols-1, detections));
			sides.add( get(pointRows-1, 0, detections));

			sides.add( get(pointRows-1, 0, detections));
			sides.add( get(0, 0, detections));
		}

		private Point2D_F64 get( int row , int col , List<Point2D_F64> detections ) {
			return detections.get(row*pointCols+col);
		}

		@Override
		public double getWidthHeightRatio() {
			return pointCols/(double)pointRows;
		}

		@Override
		public double getWidthBuffer() {
			return 1.0/(pointCols-1);
		}

		@Override
		public double getHeightBuffer() {
			return 1.0/(pointRows-1);
		}
	}
}
