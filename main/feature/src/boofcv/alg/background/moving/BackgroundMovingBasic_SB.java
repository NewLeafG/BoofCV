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

package boofcv.alg.background.moving;

import boofcv.alg.interpolate.InterpolatePixelS;
import boofcv.alg.interpolate.TypeInterpolate;
import boofcv.alg.misc.ImageMiscOps;
import boofcv.core.image.FactoryGImageSingleBand;
import boofcv.core.image.GImageSingleBand;
import boofcv.core.image.border.BorderType;
import boofcv.core.image.border.FactoryImageBorder;
import boofcv.factory.interpolate.FactoryInterpolation;
import boofcv.struct.distort.PointTransformModel_F32;
import boofcv.struct.image.ImageFloat32;
import boofcv.struct.image.ImageSingleBand;
import boofcv.struct.image.ImageType;
import boofcv.struct.image.ImageUInt8;
import georegression.struct.InvertibleTransform;

/**
 * Implementation of {@link BackgroundMovingBasic} for {@link ImageSingleBand}.
 *
 * @author Peter Abeles
 */
public class BackgroundMovingBasic_SB<T extends ImageSingleBand, Motion extends InvertibleTransform<Motion>>
	extends BackgroundMovingBasic<T,Motion>
{
	// storage for background image
	protected ImageFloat32 background = new ImageFloat32(1,1);
	// interpolates the input image
	protected InterpolatePixelS<T> interpolateInput;
	// interpolates the background image
	protected InterpolatePixelS<ImageFloat32> interpolationBG;

	// wrapper which provides abstraction across image types
	protected GImageSingleBand inputWrapper;

	public BackgroundMovingBasic_SB(float learnRate, float threshold,
									PointTransformModel_F32<Motion> transform,
									TypeInterpolate interpType,
									Class<T> imageType) {
		super(learnRate, threshold, transform, ImageType.single(imageType));

		this.interpolateInput = FactoryInterpolation.bilinearPixelS(imageType, BorderType.EXTENDED);

		this.interpolationBG = FactoryInterpolation.createPixelS(0, 255, interpType, BorderType.EXTENDED, ImageFloat32.class);
		this.interpolationBG.setBorder(FactoryImageBorder.single(ImageFloat32.class, BorderType.EXTENDED));
		this.interpolationBG.setImage(background);

		inputWrapper = FactoryGImageSingleBand.create(imageType);
	}

	/**
	 * Returns the background image.  Pixels which haven't been assigned yet are marked with {@link Float#MAX_VALUE}.
	 *
	 * @return background image.
	 */
	public ImageFloat32 getBackground() {
		return background;
	}

	@Override
	public void initialize(int backgroundWidth, int backgroundHeight, Motion homeToWorld) {
		background.reshape(backgroundWidth,backgroundHeight);
		ImageMiscOps.fill(background,Float.MAX_VALUE);

		this.homeToWorld.set(homeToWorld);
		this.homeToWorld.invert(worldToHome);

		this.backgroundWidth = backgroundWidth;
		this.backgroundHeight = backgroundHeight;
	}

	@Override
	public void reset() {
		ImageMiscOps.fill(background,Float.MAX_VALUE);
	}

	@Override
	protected void updateBackground(int x0, int y0, int x1, int y1, T frame) {
		transform.setModel(worldToCurrent);
		interpolateInput.setImage(frame);

		float minusLearn = 1.0f - learnRate;

		for (int y = y0; y < y1; y++) {
			int indexBG = background.startIndex + y*background.stride + x0;
			for (int x = x0; x < x1; x++, indexBG++ ) {
				transform.compute(x,y,work);

				if( work.x >= 0 && work.x < frame.width && work.y >= 0 && work.y < frame.height) {
					float value = interpolateInput.get(work.x,work.y);
					float bg = background.data[indexBG];

					if( bg == Float.MAX_VALUE ) {
						background.data[indexBG] = value;
					} else {
						background.data[indexBG] = minusLearn*bg + learnRate*value;
					}
				}
			}
		}
	}

	@Override
	protected void _segment(Motion currentToWorld, T frame, ImageUInt8 segmented) {
		transform.setModel(currentToWorld);
		inputWrapper.wrap(frame);

		float thresholdSq = threshold*threshold;

		for (int y = 0; y < frame.height; y++) {
			int indexFrame = frame.startIndex + y*frame.stride;
			int indexSegmented = segmented.startIndex + y*segmented.stride;

			for (int x = 0; x < frame.width; x++, indexFrame++ , indexSegmented++ ) {
				transform.compute(x,y,work);

				if( work.x >= 0 && work.x < background.width && work.y >= 0 && work.y < background.height) {
					float bg = interpolationBG.get(work.x,work.y);
					float pixelFrame = inputWrapper.getF(indexFrame);

					if( bg == Float.MAX_VALUE ) {
						segmented.data[indexSegmented] = unknownValue;
					} else {
						float diff = bg - pixelFrame;
						if (diff * diff <= thresholdSq) {
							segmented.data[indexSegmented] = 0;
						} else {
							segmented.data[indexSegmented] = 1;
						}
					}
				} else {
					// there is no background here.  Just mark it as not moving to avoid false positives
					segmented.data[indexSegmented] = unknownValue;
				}
			}
		}
	}


}
