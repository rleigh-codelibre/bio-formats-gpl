//
// ICSReader.java
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

package loci.formats.in;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.NoSuchElementException;
import java.util.StringTokenizer;
import java.util.zip.*;
import loci.formats.*;

/**
 * ICSReader is the file format reader for ICS (Image Cytometry Standard)
 * files. More information on ICS can be found at http://libics.sourceforge.net
 *
 * @author Melissa Linkert linkert at wisc.edu
 */

public class ICSReader extends FormatReader {

  // -- Fields --

  /** Current filename. */
  protected String currentIcsId;
  protected String currentIdsId;

  /** Current file. */
  protected RandomAccessStream idsIn; // IDS file
  protected Location icsIn; // ICS file

  /** Flag indicating whether current file is little endian. */
  protected boolean littleEndian;

  /** Number of images. */
  protected int numImages;

  /**
   * Dimensions in the following order:
   * 1) bits per pixel,
   * 2) width,
   * 3) height,
   * 4) z,
   * 5) channels,
   * 6) timepoints.
   */
  protected int[] dimensions = new int[6];

  /** Flag indicating whether current file is v2.0. */
  protected boolean versionTwo;

  /** Image data. */
  protected byte[] data;

  /** Dimension order. */
  private String order;

  /** Flag indicating that the images are RGB. */
  private boolean rgb;

  // -- Constructor --

  /** Constructs a new ICSReader. */
  public ICSReader() {
    super("Image Cytometry Standard", new String[] {"ics", "ids"});
  }

  // -- FormatReader API methods --

  /** Checks if the given block is a valid header for an ICS file. */
  public boolean isThisType(byte[] block) {
    return false;
  }

  /** Determines the number of images in the given ICS file. */
  public int getImageCount(String id) throws FormatException, IOException {
    if (!id.equals(currentIdsId) && !id.equals(currentIcsId)) initFile(id);
    if (numImages == 1) return 1;
    return numImages / (rgb ? dimensions[4] : 1);
  }

  /** Checks if the images in the file are RGB. */
  public boolean isRGB(String id) throws FormatException, IOException {
    if (!id.equals(currentIdsId) && !id.equals(currentIcsId)) initFile(id);
    return rgb;
  }

  /** Return true if the data is in little-endian format. */
  public boolean isLittleEndian(String id) throws FormatException, IOException {
    if (!id.equals(currentIdsId) && !id.equals(currentIcsId)) initFile(id);
    return littleEndian;
  }

  /** Returns whether or not the channels are interleaved. */
  public boolean isInterleaved(String id) throws FormatException, IOException {
    if (!id.equals(currentIdsId) && !id.equals(currentIcsId)) initFile(id);
    return !rgb;
  }

  /** Obtains the specified image from the given ICS file, as a byte array. */
  public byte[] openBytes(String id, int no)
    throws FormatException, IOException
  {
    if (!id.equals(currentId)) initFile(id); 
    byte[] buf = new byte[sizeX[0] * sizeY[0] * (dimensions[0] / 8) * 
      getRGBChannelCount(id)];
    return openBytes(id, no, buf);
  }

  public byte[] openBytes(String id, int no, byte[] buf)
    throws FormatException, IOException
  {
    if (!id.equals(currentIdsId) && !id.equals(currentIcsId)) initFile(id);
    if (no < 0 || no >= getImageCount(currentId)) {
      throw new FormatException("Invalid image number: " + no);
    }
    if (buf.length < sizeX[0] * sizeY[0] * (dimensions[0] / 8) * 
      getRGBChannelCount(id)) 
    {
      throw new FormatException("Buffer too small.");
    }
  
    int bpp = dimensions[0] / 8;

    int len = sizeX[0] * sizeY[0] * bpp * getRGBChannelCount(id);
    int offset = len * no;
    if (!rgb && sizeC[0] > 4) {
      int pt = 0;
      for (int i=no*bpp; i<data.length; i+=sizeC[0]*bpp) {
        System.arraycopy(data, i, buf, pt, bpp);
        pt += bpp;
      }
    }
    else System.arraycopy(data, offset, buf, 0, len); 

    // if it's version two, we need to flip the plane upside down
    if (versionTwo) {
      int scanline = sizeX[0] * bpp * sizeC[0]; 
      for (int y=0; y<sizeY[0]; y++) {
        for (int x=0; x<scanline; x++) {
          byte bottom = buf[y*scanline + x];
          buf[y*scanline + x] = buf[(sizeY[0] - y - 1)*scanline + x];
          buf[(sizeY[0] - y - 1)*scanline + x] = bottom; 
        }
      }
    }
    updateMinMax(buf, no);
    return buf;
  }

  /** Obtains the specified image from the given ICS file. */
  public BufferedImage openImage(String id, int no)
    throws FormatException, IOException
  {
    if (!id.equals(currentIdsId) && !id.equals(currentIcsId)) initFile(id);

    byte[] plane = openBytes(id, no);
    int width = dimensions[1];
    int height = dimensions[2];
    int channels = rgb ? dimensions[4] : 1;

    int bytes = dimensions[0] / 8;

    if (bytes == 4) {
      float[] f = new float[width * height * channels];
      int pt = 0;
      for (int i=0; i<f.length; i++) {
        int p = DataTools.bytesToInt(plane, i*4, 4, littleEndian);
        f[i] = Float.intBitsToFloat(p);
      }

      if (normalizeData) f = DataTools.normalizeFloats(f);

      BufferedImage b = ImageTools.makeImage(f, width, height, channels, true);
      updateMinMax(b, no);
      return b;
    }

    BufferedImage b = ImageTools.makeImage(plane, width, height, channels, true,
      bytes, littleEndian);
    updateMinMax(b, no);
    return b;
  }

  /* @see IFormatReader#getUsedFiles(String) */
  public String[] getUsedFiles(String id) throws FormatException, IOException {
    if (!id.equals(currentId)) initFile(id);
    if (versionTwo) return new String[] {currentIdsId};
    return new String[] {currentIdsId, currentIcsId};
  }

  /* @see IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws FormatException, IOException {
    if (fileOnly && idsIn != null) idsIn.close();
    else if (!fileOnly) close();
  }

  /** Closes any open files. */
  public void close() throws FormatException, IOException {
    if (idsIn != null) idsIn.close();
    idsIn = null;
    icsIn = null;
    currentIcsId = null;
    currentIdsId = null;
    data = null;
  }

  /** Initializes the given ICS file. */
  protected void initFile(String id) throws FormatException, IOException {
    if (debug) debug("ICSReader.initFile(" + id + ")");
    super.initFile(id);

    String icsId = id, idsId = id;
    int dot = id.lastIndexOf(".");
    String ext = dot < 0 ? "" : id.substring(dot + 1).toLowerCase();
    if(ext.equals("ics")) {
      // convert C to D regardless of case
      char[] c = idsId.toCharArray();
      c[c.length - 2]++;
      idsId = new String(c);
    }
    else if(ext.equals("ids")) {
      // convert D to C regardless of case
      char[] c = icsId.toCharArray();
      c[c.length - 2]--;
      /*id = */icsId = new String(c);
    }

    if (icsId == null) throw new FormatException("No ICS file found.");
    Location icsFile = new Location(icsId);
    if (!icsFile.exists()) throw new FormatException("ICS file not found.");

    // check if we have a v2 ICS file
    RandomAccessStream f = new RandomAccessStream(icsId);
    byte[] b = new byte[17];
    f.read(b);
    f.close();
    if (new String(b).trim().equals("ics_version\t2.0")) {
      idsIn = new RandomAccessStream(icsId);
      versionTwo = true;
    }
    else {
      if (idsId == null) throw new FormatException("No IDS file found.");
      Location idsFile = new Location(idsId);
      if (!idsFile.exists()) throw new FormatException("IDS file not found.");
      currentIdsId = idsId;
      idsIn = new RandomAccessStream(idsId);
    }

    currentIcsId = icsId;

    icsIn = icsFile;

    RandomAccessStream reader = new RandomAccessStream(icsIn.getAbsolutePath());
    StringTokenizer t;
    String token;
    b = new byte[(int) reader.length()];
    reader.read(b);
    reader.close();
    String s = new String(b);
    StringTokenizer st = new StringTokenizer(s, "\n");
    String line = st.nextToken();
    line = st.nextToken();
    while (line != null && !line.trim().equals("end")) {
      t = new StringTokenizer(line);
      while(t.hasMoreTokens()) {
        token = t.nextToken();
        if (!token.equals("layout") && !token.equals("representation") &&
          !token.equals("parameter") && !token.equals("history") &&
          !token.equals("sensor"))
        {
          if (t.countTokens() < 3) {
            try {
              addMeta(token, t.nextToken());
            }
            catch (NoSuchElementException e) { }
          }
          else {
            String meta = t.nextToken();
            while (t.hasMoreTokens()) {
              meta = meta + " " + t.nextToken();
            }
            addMeta(token, meta);
          }
        }
      }
      try {
        line = st.nextToken();
      }
      catch (NoSuchElementException e) { line = null; }
    }

    String images = (String) getMeta("sizes");
    String ord = (String) getMeta("order");
    ord = ord.trim();
    // bpp, width, height, z, channels
    StringTokenizer t1 = new StringTokenizer(images);
    StringTokenizer t2 = new StringTokenizer(ord);

    for(int i=0; i<dimensions.length; i++) {
      dimensions[i] = 1;
    }

    rgb = ord.indexOf("ch") >= 0 && ord.indexOf("ch") < ord.indexOf("x");

    String imageToken;
    String orderToken;
    while (t1.hasMoreTokens() && t2.hasMoreTokens()) {
      imageToken = t1.nextToken().trim();
      orderToken = t2.nextToken().trim();
      if (orderToken.equals("bits")) {
        dimensions[0] = Integer.parseInt(imageToken);
      }
      else if(orderToken.equals("x")) {
        dimensions[1] = Integer.parseInt(imageToken);
      }
      else if(orderToken.equals("y")) {
        dimensions[2] = Integer.parseInt(imageToken);
      }
      else if(orderToken.equals("z")) {
        dimensions[3] = Integer.parseInt(imageToken);
      }
      else if(orderToken.equals("ch")) {
        dimensions[4] = Integer.parseInt(imageToken);
        if (dimensions[4] > 4) rgb = false; 
      }
      else {
        dimensions[5] = Integer.parseInt(imageToken);
      }
    }

    int width = dimensions[1];
    int height = dimensions[2];

    numImages = dimensions[3] * dimensions[4] * dimensions[5];
    if (numImages == 0) numImages++;

    String endian = (String) getMeta("byte_order");
    littleEndian = true;

    if (endian != null) {
      StringTokenizer endianness = new StringTokenizer(endian);
      String firstByte = endianness.nextToken();
      int first = Integer.parseInt(firstByte);
      littleEndian = ((String) getMeta("format")).equals("real") ? 
        first == 1 : first != 1;
    }

    String test = (String) getMeta("compression");
    boolean gzip = (test == null) ? false : test.equals("gzip");

    if (versionTwo) {
      s = idsIn.readLine();
      while(!s.trim().equals("end")) s = idsIn.readLine();
    }
    data = new byte[(int) (idsIn.length() - idsIn.getFilePointer())];

    // extra check is because some of our datasets are labeled as 'gzip', and
    // have a valid GZIP header, but are actually uncompressed
    if (gzip &&
      ((data.length / (numImages) < (width * height * dimensions[0]/8))))
    {
      idsIn.read(data);
      byte[] buf = new byte[8192];
      ByteVector v = new ByteVector();
      try {
        GZIPInputStream decompressor =
          new GZIPInputStream(new ByteArrayInputStream(data));
        int r = decompressor.read(buf, 0, buf.length);
        while (r > 0) {
          v.add(buf, 0, r);
          r = decompressor.read(buf, 0, buf.length);
        }
        data = v.toByteArray();
      }
      catch (IOException dfe) {
        throw new FormatException("Error uncompressing gzip'ed data", dfe);
      }
    }
    else idsIn.readFully(data);

    // Populate metadata store

    // The metadata store we're working with.
    MetadataStore store = getMetadataStore(id);

    store.setImage((String) getMeta("filename"), null, null, null);

    // populate Pixels element

    String o = (String) getMeta("order");
    o = o.trim();
    o = o.substring(o.indexOf("x")).trim();
    char[] tempOrder = new char[(o.length() / 2) + 1];
    int pt = 0;
    for (int i=0; i<o.length(); i+=2) {
      tempOrder[pt] = o.charAt(i);
      pt++;
    }
    o = new String(tempOrder).toUpperCase().trim();
    if (o.indexOf("Z") == -1) o = o + "Z";
    if (o.indexOf("T") == -1) o = o + "T";
    if (o.indexOf("C") == -1) o = o + "C";

    int bitsPerPixel =
      Integer.parseInt((String) getMeta("significant_bits"));
    String fmt = (String) getMeta("format");
    String sign = (String) getMeta("sign");

    if (bitsPerPixel < 32) littleEndian = !littleEndian;

    if (fmt.equals("real")) pixelType[0] = FormatReader.FLOAT;
    else if (fmt.equals("integer")) {
      while (bitsPerPixel % 8 != 0) bitsPerPixel++;
      if (bitsPerPixel == 24 || bitsPerPixel == 48) bitsPerPixel /= 3;

      switch (bitsPerPixel) {
        case 8:
          pixelType[0] = FormatReader.UINT8;
          break;
        case 16:
          pixelType[0] = FormatReader.UINT16;
          break;
        case 32:
          pixelType[0] = FormatReader.UINT32;
          break;
      }
    }
    else {
      throw new RuntimeException("Unknown pixel format: " + format);
    }

    order = o;

    sizeX[0] = dimensions[1];
    sizeY[0] = dimensions[2];
    sizeZ[0] = dimensions[3];
    sizeC[0] = dimensions[4];
    sizeT[0] = dimensions[5];
    currentOrder[0] = order.trim();

    store.setPixels(
      new Integer(dimensions[1]), // SizeX
      new Integer(dimensions[2]), // SizeY
      new Integer(dimensions[3]), // SizeZ
      new Integer(dimensions[4]), // SizeC
      new Integer(dimensions[5]), // SizeT
      new Integer(pixelType[0]), // PixelType
      new Boolean(!littleEndian), // BigEndian
      order.trim(), // DimensionOrder
      null, // Use image index 0
      null); // Use pixels index 0

    for (int i=0; i<sizeC[0]; i++) {
      store.setLogicalChannel(i, null, null, null, null, null, null, null);
    }
  }

  // -- Main method --

  public static void main(String[] args) throws FormatException, IOException {
    new ICSReader().testRead(args);
  }

}
