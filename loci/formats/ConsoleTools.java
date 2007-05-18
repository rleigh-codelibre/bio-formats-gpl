//
// ConsoleTools.java
//

/*
LOCI Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ Melissa Linkert, Curtis Rueden, Chris Allan,
Eric Kjellman and Brian Loranger.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Library General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Library General Public License for more details.

You should have received a copy of the GNU Library General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Hashtable;

/** A utility class for command line tools. */
public final class ConsoleTools {

  // -- Constructor --

  private ConsoleTools() { }

  // -- Utility methods - command line --

  /**
   * A utility method for reading a file from the command line,
   * and displaying the results in a simple display.
   */
  public static boolean testRead(IFormatReader reader, String[] args)
    throws FormatException, IOException
  {
    String id = null;
    boolean pixels = true;
    boolean doMeta = true;
    boolean thumbs = false;
    boolean minmax = false;
    boolean merge = false;
    boolean stitch = false;
    boolean separate = false;
    boolean omexml = false;
    boolean normalize = false;
    boolean fastBlit = false;
    int start = 0;
    int end = Integer.MAX_VALUE;
    int series = 0;
    String map = null;
    if (args != null) {
      for (int i=0; i<args.length; i++) {
        if (args[i].startsWith("-") && args.length > 1) {
          if (args[i].equals("-nopix")) pixels = false;
          else if (args[i].equals("-nometa")) doMeta = false;
          else if (args[i].equals("-thumbs")) thumbs = true;
          else if (args[i].equals("-minmax")) minmax = true;
          else if (args[i].equals("-merge")) merge = true;
          else if (args[i].equals("-stitch")) stitch = true;
          else if (args[i].equals("-separate")) separate = true;
          else if (args[i].equals("-omexml")) omexml = true;
          else if (args[i].equals("-normalize")) normalize = true;
          else if (args[i].equals("-fast")) fastBlit = true;
          else if (args[i].equals("-debug")) FormatHandler.setDebug(true);
          else if (args[i].equals("-level")) {
            try {
              FormatHandler.setDebugLevel(Integer.parseInt(args[++i]));
            }
            catch (NumberFormatException exc) { }
          }
          else if (args[i].equals("-range")) {
            try {
              start = Integer.parseInt(args[++i]);
              end = Integer.parseInt(args[++i]);
            }
            catch (NumberFormatException exc) { }
          }
          else if (args[i].equals("-series")) {
            try {
              series = Integer.parseInt(args[++i]);
            }
            catch (NumberFormatException exc) { }
          }
          else if (args[i].equals("-map")) map = args[++i];
          else System.out.println("Ignoring unknown command flag: " + args[i]);
        }
        else {
          if (id == null) id = args[i];
          else System.out.println("Ignoring unknown argument: " + args[i]);
        }
      }
    }
    if (FormatHandler.debug) {
      System.out.println("Debugging at level " + FormatHandler.debugLevel);
    }
    if (id == null) {
      String className = reader.getClass().getName();
      String format = reader.getFormat();
      String[] s = {
        "To test read a file in " + format + " format, run:",
        "  java " + className + " [-nopix] [-nometa] [-thumbs] [-minmax]",
        "    [-merge] [-stitch] [-separate] [-omexml] [-normalize]",
        "    [-fast] [-debug] [-range start end] [-series num] [-map id] file",
        "",
        "      file: the image file to read",
        "    -nopix: read metadata only, not pixels",
        "   -nometa: output only core metadata",
        "   -thumbs: read thumbnails instead of normal pixels",
        "   -minmax: compute min/max statistics",
        "    -merge: combine separate channels into RGB image",
        "   -stitch: stitch files with similar names",
        " -separate: split RGB image into separate channels",
        "   -omexml: populate OME-XML metadata",
        "-normalize: normalize floating point images*",
        "     -fast: paint RGB images as quickly as possible*",
        "    -debug: turn on debugging output",
        "    -range: specify range of planes to read (inclusive)",
        "   -series: specify which image series to read",
        "      -map: specify file on disk to which name should be mapped",
        "",
        "* = may result in loss of precision",
        ""
      };
      for (int i=0; i<s.length; i++) System.out.println(s[i]);
      return false;
    }
    if (map != null) Location.mapId(id, map);
    if (omexml) {
      try {
        // NB: avoid dependencies on optional loci.formats.ome package
        Class c = Class.forName("loci.formats.ome.OMEXMLMetadataStore");
        MetadataStore ms = (MetadataStore) c.newInstance();
        reader.setMetadataStore(ms);
      }
      catch (Throwable t) {
        // NB: error messages for missing OME-Java are printed later
      }
    }

    // check file format
    if (reader instanceof ImageReader) {
      // determine format
      ImageReader ir = (ImageReader) reader;
      System.out.print("Checking file format ");
      System.out.println("[" + ir.getFormat(id) + "]");
    }
    else {
      // verify format
      System.out.print("Checking " + reader.getFormat() + " format ");
      System.out.println(reader.isThisType(id) ? "[yes]" : "[no]");
    }

    System.out.println("Initializing reader");
    if (stitch) {
      reader = new FileStitcher(reader, true);
      String pat = FilePattern.findPattern(new Location(id));
      if (pat != null) id = pat;
    }
    if (separate) reader = new ChannelSeparator(reader);
    if (merge) reader = new ChannelMerger(reader);
    MinMaxCalculator minMaxCalc = null;
    if (minmax) reader = minMaxCalc = new MinMaxCalculator(reader);

    StatusEchoer status = new StatusEchoer();
    reader.addStatusListener(status);

    reader.close();
    reader.setNormalized(normalize);
    reader.setMetadataFiltered(true);
    reader.setMetadataCollected(doMeta);
    long s1 = System.currentTimeMillis();
    reader.setId(id);
    long e1 = System.currentTimeMillis();
    float sec1 = (e1 - s1) / 1000f;
    System.out.println("Initialization took " + sec1 + "s");

    if (!normalize && reader.getPixelType() == FormatTools.FLOAT) {
      throw new FormatException("Sorry, unnormalized floating point " +
        "data is not supported. Please use the '-normalize' option.");
    }

    // read basic metadata
    System.out.println();
    System.out.println("Reading core metadata");
    System.out.println(stitch ?
      "File pattern = " + id : "Filename = " + reader.getCurrentFile());
    if (map != null) System.out.println("Mapped filename = " + map);
    String[] used = reader.getUsedFiles();
    boolean usedValid = used != null && used.length > 0;
    if (usedValid) {
      for (int u=0; u<used.length; u++) {
        if (used[u] == null) {
          usedValid = false;
          break;
        }
      }
    }
    if (!usedValid) {
      System.out.println(
        "************ Warning: invalid used files list ************");
    }
    if (used == null) {
      System.out.println("Used files = null");
    }
    else if (used.length == 0) {
      System.out.println("Used files = []");
    }
    else if (used.length > 1) {
      System.out.println("Used files:");
      for (int u=0; u<used.length; u++) System.out.println("\t" + used[u]);
    }
    else if (!id.equals(used[0])) {
      System.out.println("Used files = [" + used[0] + "]");
    }
    int seriesCount = reader.getSeriesCount();
    System.out.println("Series count = " + seriesCount);
    for (int j=0; j<seriesCount; j++) {
      reader.setSeries(j);

      // read basic metadata for series #i
      int imageCount = reader.getImageCount();
      boolean rgb = reader.isRGB();
      int sizeX = reader.getSizeX();
      int sizeY = reader.getSizeY();
      int sizeZ = reader.getSizeZ();
      int sizeC = reader.getSizeC();
      int sizeT = reader.getSizeT();
      int pixelType = reader.getPixelType();
      int effSizeC = reader.getEffectiveSizeC();
      int rgbChanCount = reader.getRGBChannelCount();
      int[] cLengths = reader.getChannelDimLengths();
      String[] cTypes = reader.getChannelDimTypes();
      int thumbSizeX = reader.getThumbSizeX();
      int thumbSizeY = reader.getThumbSizeY();
      boolean little = reader.isLittleEndian();
      String dimOrder = reader.getDimensionOrder();
      boolean orderCertain = reader.isOrderCertain();
      boolean interleaved = reader.isInterleaved();

      // output basic metadata for series #i
      System.out.println("Series #" + j + ":");
      System.out.println("\tImage count = " + imageCount);
      System.out.print("\tRGB = " + rgb + " (" + rgbChanCount + ")");
      if (merge) System.out.print(" (merged)");
      else if (separate) System.out.print(" (separated)");
      if (rgb != (rgbChanCount != 1)) {
        System.out.println("\t************ Warning: RGB mismatch ************");
      }
      System.out.println();
      System.out.println("\tInterleaved = " + interleaved);
      System.out.println("\tWidth = " + sizeX);
      System.out.println("\tHeight = " + sizeY);
      System.out.println("\tSizeZ = " + sizeZ);
      System.out.print("\tSizeC = " + sizeC);
      if (sizeC != effSizeC) {
        System.out.print(" (effectively " + effSizeC + ")");
      }
      System.out.println();
      int cProduct = 1;
      for (int i=0; i<cLengths.length; i++) {
        System.out.println("\t\t#" + (i + 1) + " = " +
          cTypes[i] + " (" + cLengths[i] + ")");
        cProduct *= cLengths[i];
      }
      if (cLengths.length == 0 || cProduct != sizeC) {
        System.out.println(
          "\t************ Warning: C dimension mismatch ************");
      }
      System.out.println("\tSizeT = " + sizeT);
      if (imageCount != sizeZ * effSizeC * sizeT) {
        System.out.println("\t************ Warning: ZCT mismatch ************");
      }
      System.out.println("\tThumbnail size = " +
        thumbSizeX + " x " + thumbSizeY);
      System.out.println("\tEndianness = " +
        (little ? "intel (little)" : "motorola (big)"));
      System.out.println("\tDimension order = " + dimOrder +
        (orderCertain ? " (certain)" : " (uncertain)"));
      System.out.println("\tPixel type = " +
        FormatTools.getPixelTypeString(pixelType));
      if (doMeta) {
        System.out.println("\t-----");
        int[] indices;
        if (imageCount > 6) {
          int q = imageCount / 2;
          indices = new int[] {
            0, q - 2, q - 1, q, q + 1, q + 2, imageCount - 1
          };
        }
        else if (imageCount > 2) {
          indices = new int[] {0, imageCount / 2, imageCount - 1};
        }
        else if (imageCount > 1) indices = new int[] {0, 1};
        else indices = new int[] {0};
        int[][] zct = new int[indices.length][];
        int[] indices2 = new int[indices.length];
        for (int i=0; i<indices.length; i++) {
          zct[i] = reader.getZCTCoords(indices[i]);
          indices2[i] = reader.getIndex(zct[i][0], zct[i][1], zct[i][2]);
          System.out.print("\tPlane #" + indices[i] + " <=> Z " + zct[i][0] +
            ", C " + zct[i][1] + ", T " + zct[i][2]);
          if (indices[i] != indices2[i]) {
            System.out.println(" [mismatch: " + indices2[i] + "]");
          }
          else System.out.println();
        }
      }
    }
    reader.setSeries(series);
    String s = seriesCount > 1 ? (" series #" + series) : "";
    int pixelType = reader.getPixelType();
    int sizeC = reader.getSizeC();

    // get a priori min/max values
    Double[] preGlobalMin = null, preGlobalMax = null;
    Double[] preKnownMin = null, preKnownMax = null;
    Double[] prePlaneMin = null, prePlaneMax = null;
    boolean preIsMinMaxPop = false;
    if (minmax) {
      preGlobalMin = new Double[sizeC];
      preGlobalMax = new Double[sizeC];
      preKnownMin = new Double[sizeC];
      preKnownMax = new Double[sizeC];
      for (int c=0; c<sizeC; c++) {
        preGlobalMin[c] = minMaxCalc.getChannelGlobalMinimum(c);
        preGlobalMax[c] = minMaxCalc.getChannelGlobalMaximum(c);
        preKnownMin[c] = minMaxCalc.getChannelKnownMinimum(c);
        preKnownMax[c] = minMaxCalc.getChannelKnownMaximum(c);
      }
      prePlaneMin = minMaxCalc.getPlaneMinimum(0);
      prePlaneMax = minMaxCalc.getPlaneMaximum(0);
      preIsMinMaxPop = minMaxCalc.isMinMaxPopulated();
    }

    // read pixels
    if (pixels) {
      System.out.println();
      System.out.print("Reading" + s + " pixel data ");
      status.setVerbose(false);
      int num = reader.getImageCount();
      if (start < 0) start = 0;
      if (start >= num) start = num - 1;
      if (end < 0) end = 0;
      if (end >= num) end = num - 1;
      if (end < start) end = start;

      System.out.print("(" + start + "-" + end + ") ");
      BufferedImage[] images = new BufferedImage[end - start + 1];
      long s2 = System.currentTimeMillis();
      boolean mismatch = false;
      for (int i=start; i<=end; i++) {
        status.setEchoNext(true);
        if (!fastBlit) {
          images[i - start] = thumbs ?
            reader.openThumbImage(i) : reader.openImage(i);
        }
        else {
          int x = reader.getSizeX();
          int y = reader.getSizeY();
          byte[] b = thumbs ? reader.openThumbBytes(i) :
            reader.openBytes(i);
          Object pix = DataTools.makeDataArray(b,
            FormatTools.getBytesPerPixel(reader.getPixelType()),
            reader.getPixelType() == FormatTools.FLOAT,
            reader.isLittleEndian());
          images[i - start] =
            ImageTools.makeImage(ImageTools.make24Bits(pix, x, y,
              false, false), x, y);
        }

        // check for pixel type mismatch
        int pixType = ImageTools.getPixelType(images[i - start]);
        if (pixType != pixelType && !fastBlit) {
          if (!mismatch) {
            System.out.println();
            mismatch = true;
          }
          System.out.println("\tPlane #" + i + ": pixel type mismatch: " +
            FormatTools.getPixelTypeString(pixType) + "/" +
            FormatTools.getPixelTypeString(pixelType));
        }
        else {
          mismatch = false;
          System.out.print(".");
        }
      }
      long e2 = System.currentTimeMillis();
      if (!mismatch) System.out.print(" ");
      System.out.println("[done]");

      // output timing results
      float sec2 = (e2 - s2) / 1000f;
      float avg = (float) (e2 - s2) / images.length;
      System.out.println(sec2 + "s elapsed (" + avg + "ms per image)");

      if (minmax) {
        // get computed min/max values
        Double[] globalMin = new Double[sizeC];
        Double[] globalMax = new Double[sizeC];
        Double[] knownMin = new Double[sizeC];
        Double[] knownMax = new Double[sizeC];
        for (int c=0; c<sizeC; c++) {
          globalMin[c] = minMaxCalc.getChannelGlobalMinimum(c);
          globalMax[c] = minMaxCalc.getChannelGlobalMaximum(c);
          knownMin[c] = minMaxCalc.getChannelKnownMinimum(c);
          knownMax[c] = minMaxCalc.getChannelKnownMaximum(c);
        }
        Double[] planeMin = minMaxCalc.getPlaneMinimum(0);
        Double[] planeMax = minMaxCalc.getPlaneMaximum(0);
        boolean isMinMaxPop = minMaxCalc.isMinMaxPopulated();

        // output min/max results
        System.out.println();
        System.out.println("Min/max values:");
        for (int c=0; c<sizeC; c++) {
          System.out.println("\tChannel " + c + ":");
          System.out.println("\t\tGlobal minimum = " +
            globalMin[c] + " (initially " + preGlobalMin[c] + ")");
          System.out.println("\t\tGlobal maximum = " +
            globalMax[c] + " (initially " + preGlobalMax[c] + ")");
          System.out.println("\t\tKnown minimum = " +
            knownMin[c] + " (initially " + preKnownMin[c] + ")");
          System.out.println("\t\tKnown maximum = " +
            knownMax[c] + " (initially " + preKnownMax[c] + ")");
        }
        System.out.print("\tFirst plane minimum(s) =");
        if (planeMin == null) System.out.print(" none");
        else {
          for (int subC=0; subC<planeMin.length; subC++) {
            System.out.print(" " + planeMin[subC]);
          }
        }
        System.out.print(" (initially");
        if (prePlaneMin == null) System.out.print(" none");
        else {
          for (int subC=0; subC<prePlaneMin.length; subC++) {
            System.out.print(" " + prePlaneMin[subC]);
          }
        }
        System.out.println(")");
        System.out.print("\tFirst plane maximum(s) =");
        if (planeMax == null) System.out.print(" none");
        else {
          for (int subC=0; subC<planeMax.length; subC++) {
            System.out.print(" " + planeMax[subC]);
          }
        }
        System.out.print(" (initially");
        if (prePlaneMax == null) System.out.print(" none");
        else {
          for (int subC=0; subC<prePlaneMax.length; subC++) {
            System.out.print(" " + prePlaneMax[subC]);
          }
        }
        System.out.println(")");
        System.out.println("\tMin/max populated = " +
          isMinMaxPop + " (initially " + preIsMinMaxPop + ")");
      }

      // display pixels in image viewer
      // NB: avoid dependencies on optional loci.formats.gui package
      ReflectedUniverse r = new ReflectedUniverse();
      try {
        r.exec("import loci.formats.gui.ImageViewer");
        r.exec("viewer = new ImageViewer()");
        r.setVar("reader", reader);
        r.setVar("images", images);
        r.setVar("true", true);
        r.exec("viewer.setImages(reader, images)");
        r.exec("viewer.setVisible(true)");
      }
      catch (ReflectException exc) {
        throw new FormatException(exc);
      }
    }

    // read format-specific metadata table
    if (doMeta) {
      System.out.println();
      System.out.println("Reading" + s + " metadata");
      Hashtable meta = reader.getMetadata();
      String[] keys = (String[]) meta.keySet().toArray(new String[0]);
      Arrays.sort(keys);
      for (int i=0; i<keys.length; i++) {
        System.out.print(keys[i] + ": ");
        System.out.println(reader.getMetadataValue(keys[i]));
      }
    }

    // output OME-XML
    if (omexml) {
      System.out.println();
      System.out.println("Generating OME-XML");
      MetadataStore ms = reader.getMetadataStore();

      // NB: avoid dependencies on optional loci.formats.ome package
      if (ms.getClass().getName().equals(
        "loci.formats.ome.OMEXMLMetadataStore"))
      {
        try {
          Method m = ms.getClass().getMethod("dumpXML", (Class[]) null);
          String xml = (String) m.invoke(ms, (Object[]) null);
          System.out.println(FormatTools.indentXML(xml));
        }
        catch (Throwable t) {
          System.out.println("Error generating OME-XML:");
          t.printStackTrace();
        }
      }
      else {
        System.out.println("OME-Java library not found; no OME-XML available");
      }
    }

    return true;
  }

  /** A utility method for converting a file from the command line. */
  public static boolean testConvert(IFormatWriter writer, String[] args)
    throws FormatException, IOException
  {
    String in = null, out = null;
    if (args != null) {
      for (int i=0; i<args.length; i++) {
        if (args[i].startsWith("-") && args.length > 1) {
          if (args[i].equals("-debug")) FormatHandler.setDebug(true);
          else System.out.println("Ignoring unknown command flag: " + args[i]);
        }
        else {
          if (in == null) in = args[i];
          else if (out == null) out = args[i];
          else System.out.println("Ignoring unknown argument: " + args[i]);
        }
      }
    }
    if (FormatHandler.debug) {
      System.out.println("Debugging at level " + FormatHandler.debugLevel);
    }
    String className = writer.getClass().getName();
    if (in == null || out == null) {
      System.out.println("To convert a file to " + writer.getFormat() +
        " format, run:");
      System.out.println("  java " + className + " [-debug] in_file out_file");
      return false;
    }

    long start = System.currentTimeMillis();
    System.out.print(in + " ");
    ImageReader reader = new ImageReader();
    reader.setId(in);
    System.out.print("[" + reader.getFormat() + "] -> " + out + " ");
    writer.setId(out);
    System.out.print("[" + writer.getFormat() + "] ");
    long mid = System.currentTimeMillis();

    int num = writer.canDoStacks() ? reader.getImageCount() : 1;
    long read = 0, write = 0;
    for (int i=0; i<num; i++) {
      long s = System.currentTimeMillis();
      Image image = reader.openImage(i);
      long m = System.currentTimeMillis();
      writer.saveImage(image, i == num - 1);
      long e = System.currentTimeMillis();
      System.out.print(".");
      read += m - s;
      write += e - m;
    }
    long end = System.currentTimeMillis();
    System.out.println(" [done]");

    // output timing results
    float sec = (end - start) / 1000f;
    long initial = mid - start;
    float readAvg = (float) read / num;
    float writeAvg = (float) write / num;
    System.out.println(sec + "s elapsed (" +
      readAvg + "+" + writeAvg + "ms per image, " + initial + "ms overhead)");

    return true;
  }

  // -- Helper classes --

  /** Used by testRead to echo status messages to the console. */
  private static class StatusEchoer implements StatusListener {
    private boolean verbose = true;
    private boolean next = true;

    public void setVerbose(boolean value) { verbose = value; }
    public void setEchoNext(boolean value) { next = value; }

    public void statusUpdated(StatusEvent e) {
      if (verbose) System.out.println("\t" + e.getStatusMessage());
      else if (next) {
        System.out.print(";");
        next = false;
      }
    }
  }

}
