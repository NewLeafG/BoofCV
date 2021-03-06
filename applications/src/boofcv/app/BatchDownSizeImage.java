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

package boofcv.app;

import boofcv.alg.filter.misc.AverageDownSampleOps;
import boofcv.io.image.ConvertBufferedImage;
import boofcv.io.image.UtilImageIO;
import boofcv.misc.BoofMiscOps;
import boofcv.struct.image.ImageBase;
import boofcv.struct.image.ImageSingleBand;
import boofcv.struct.image.ImageUInt8;
import boofcv.struct.image.MultiSpectral;

import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Loads a set of images, resizes them to a smaller size using an intelligent algorithm then saves them.
 *
 * @author Peter Abeles
 */
public class BatchDownSizeImage {

	public static List<File> images;
	public static File outputDir;
	// if true it will rename the input to files with consecutive numbers
	public static boolean rename = false;
	public static int width=0,height=0,side=0;
	// should it set the size using "side"
	public static boolean useSide = false;

	public static void printHelpAndExit(String[] args) {
		System.out.println("=== Usage");
		System.out.println("BatchDownSizeImage <flags>  <input file path regex> <output> <width> <height>");
		System.out.println("BatchDownSizeImage <flags>  <input file path regex> <output> <max length>");
		System.out.println();
		System.out.println("Downsizes multiple files at once using average resampling.  When resizing a image "+
				"to less than 50% of its original size this will typically perform much better than "+
				"standard interpolation techniques.  Output images are in png format.");
		System.out.println();
		System.out.println("=== Flags");
		System.out.println("-rename    Renames the output files to image%05d.png");
		System.out.println();
		System.out.println("=== Arguments");
		System.out.println("First argument is a Java regex for input files.");
		System.out.println("   /path/to/input/directory/\\^\\\\w*.jpg");
		System.out.println("Second argument is a path to the output directory");
		System.out.println("If there are 4 arguments then last two is the width and height");
		System.out.println("   either width or height can be zero.  If zero then it will maintain the aspect");
		System.out.println("   ratio of the input file.");
		System.out.println("If there are 3 arguments then that value is the length of the largest side");
		System.out.println("   The other side will be set according to the aspect ratio.");
		System.exit(0);
	}

	private static void parseArguments(String[] args) {
		int start = 0;
		for (; start < args.length; start++) {
			String s = args[start];
			if( s.charAt(0) == '-') {
				if( s.substring(1,s.length()).compareToIgnoreCase("rename") == 0 ) {
					rename = true;
				} else {
					printHelpAndExit(args);
				}
			} else {
				break;
			}
		}

		if( args.length-start > 4 || args.length-start < 3 )
			printHelpAndExit(args);

		String fileRegex = args[start];
		String output = args[start+1];

		if( args.length-start == 3 ) {
			useSide = true;
			side = Integer.parseInt(args[start+2]);
			if( side <= 0 )
				printHelpAndExit(args);
		} else {
			width = Integer.parseInt(args[start+2]);
			height = Integer.parseInt(args[start+3]);
			if( width <= 0 && height <= 0 )
				printHelpAndExit(args);
		}

		outputDir = new File(output);
		if( !outputDir.exists() )
			if( !outputDir.mkdirs() )
				throw new IllegalArgumentException("Can't create output directory: "+output);

		images = Arrays.asList(BoofMiscOps.findMatches(fileRegex));
		Collections.sort(images);

		if( images.size() == 0 ) {
			System.out.println(fileRegex);
			System.err.println("No images found.  Is the path/regex correct?");
			System.exit(1);
		}
	}

	public static void main(String[] args) {

		parseArguments(args);

		ImageBase input = new ImageUInt8(1,1);
		ImageBase small = null;

		int index = 0;
		for ( File f : images) {
			BufferedImage orig = UtilImageIO.loadImage(f.getPath());

			WritableRaster info = orig.getRaster();

			boolean missMatch = false;
			if( input.getWidth() != info.getWidth() || input.getHeight() != info.getHeight()) {
				missMatch = true;
			} if( info.getNumBands() == 1 ) {
				if( !(input instanceof ImageSingleBand) )
					missMatch = true;
			} else {
				if( !(input instanceof MultiSpectral)) {
					missMatch = true;
				} else if( info.getNumBands() != ((MultiSpectral)input).getNumBands() )  {
					missMatch = true;
				}
			}

			if( missMatch ) {
				// declare the BoofCV image to conver the input into
				if( info.getNumBands() == 1 ) {
					input = new ImageUInt8(info.getWidth(),info.getHeight());
				} else {
					input = new MultiSpectral<ImageUInt8>(ImageUInt8.class,info.getWidth(),info.getHeight(),info.getNumBands());
				}

				// Now declare storage for the small image
				int smallHeight,smallWidth;
				if( useSide ) {
					if( input.getWidth() > input.getHeight() ) {
						width = side;
						height = 0;
					} else {
						height = side;
						width = 0;
					}
				}

				if( height == 0 ) {
					smallWidth = width;
					smallHeight = input.getHeight()*width/input.getWidth();
				} else if( width == 0 ) {
					smallWidth = input.getWidth()*height/input.getHeight();
					smallHeight = height;
				} else {
					smallWidth = width;
					smallHeight = height;
				}
				small = input._createNew(smallWidth,smallHeight);
			}

			System.out.printf(" %4d out of %4d   %s\n",index+1,images.size(),f.getName());

			ConvertBufferedImage.convertFrom(orig, input, true);
			AverageDownSampleOps.down(input,small);

			String nout;
			if( rename ) {
				nout = String.format("image%05d.png",index);
			} else {
				nout = f.getName();
				nout = nout.substring(0, nout.length() - 3) + "png";
			}

			File fout = new File(outputDir,nout);
			UtilImageIO.saveImage(small, fout.getPath());

			index++;
		}
		System.out.println("Done");
	}
}
