/*
 * Copyright (c) 2011-2014, Peter Abeles. All Rights Reserved.
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

package boofcv.alg.segmentation;

import boofcv.alg.interpolate.InterpolatePixelS;
import boofcv.alg.misc.ImageMiscOps;
import boofcv.alg.weights.*;
import boofcv.factory.interpolate.FactoryInterpolation;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSInt32;
import org.ddogleg.struct.FastQueue;
import org.ddogleg.struct.GrowQueue_I32;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
* @author Peter Abeles
*/
public class TestSegmentMeanShiftGray {

	Random rand = new Random(234);

	InterpolatePixelS<ImageFloat32> interp = FactoryInterpolation.bilinearPixelS(ImageFloat32.class);
	// use a gaussian distribution by default so that the indexes matter
	WeightPixel_F32 weightSpacial = new WeightPixelGaussian_F32();
	WeightDistance_F32 weightDist = new WeightDistanceUniform_F32(200);

	@Before
	public void before() {
		weightSpacial.setRadius(2,2);
	}

	/**
	 * Process a random image and do a basic sanity check on the output
	 */
	@Test
	public void simpleTest() {
		ImageFloat32 image = new ImageFloat32(20,25);

		ImageMiscOps.fillUniform(image, rand, 0, 256);

		SegmentMeanShiftGray<ImageFloat32> alg =
				new SegmentMeanShiftGray<ImageFloat32>(30,0.05f,interp,weightSpacial, weightDist);

		alg.process(image);

		GrowQueue_I32 locations = alg.getModeLocation();
		GrowQueue_I32 counts = alg.getSegmentMemberCount();
		ImageSInt32 peaks = alg.getPixelToMode();
		FastQueue<float[]> values = alg.getModeColor();

		// there should be a fair number of local peaks due to the image being random
		assertTrue( locations.size > 20 );
		// all the lists should be the same size
		assertEquals(locations.size,counts.size);
		assertEquals(locations.size,values.size);

		// total members should equal the number of pixels
		int totalMembers = 0;
		for( int i = 0; i < counts.size; i++ ) {
			totalMembers += counts.get(i);
		}
		assertEquals(20*25,totalMembers);

		// see if the peak to index image is set up correctly and that all the peaks make sense
		for( int y = 0; y < peaks.height; y++ ) {
			for( int x = 0; x < peaks.width; x++ ) {
				int peak = peaks.get(x,y);

				// can't test the value because its floating point location which is interpolated using the kernel
				// and the location is lost
//				assertEquals(x+" "+y,computeValue(peakX,peakY,image),value,50);

				assertTrue( counts.get(peak) > 0 );
			}
		}
	}

	@Test
	public void findPeak_inside() {
		ImageFloat32 image = new ImageFloat32(20,25);

		ImageMiscOps.fillRectangle(image, 20, 4, 2, 5, 5);

		// works better with this example when its uniform
		WeightPixel_F32 weightSpacial = new WeightPixelUniform_F32();
		weightSpacial.setRadius(2,2);
		SegmentMeanShiftGray<ImageFloat32> alg =
				new SegmentMeanShiftGray<ImageFloat32>(30,0.05f,interp,weightSpacial, weightDist);

		interp.setImage(image);
		alg.image = image;
		alg.findPeak(4,2,20);

		assertEquals( 6 , alg.meanX , 0.5f );
		assertEquals( 4 , alg.meanY , 0.5f );
	}

	@Test
	public void findPeak_border() {
		findPeak_border(2, 2, 0, 0);
		findPeak_border(17, 22, 19, 24);
	}

	private void findPeak_border(int cx, int cy, int startX, int startY) {
		ImageFloat32 image = new ImageFloat32(20,25);

		ImageMiscOps.fillRectangle(image, 20, cx - 2, cy - 2, 5, 5);

		SegmentMeanShiftGray<ImageFloat32> alg =
				new SegmentMeanShiftGray<ImageFloat32>(30,0.05f,interp,weightSpacial, weightDist);

		interp.setImage(image);
		alg.image = image;
		alg.findPeak(startX,startY,20);

		assertEquals( cx , alg.meanX , 0.5f );
		assertEquals( cy , alg.meanY , 0.5f );
	}
}