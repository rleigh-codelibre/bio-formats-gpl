//
// AVIReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.io.*;
import java.util.Vector;
import loci.formats.*;
import loci.formats.codec.*;
import loci.formats.meta.FilterMetadata;
import loci.formats.meta.MetadataStore;

/**
 * AVIReader is the file format reader for AVI files.
 *
 * Much of this code was adapted from Wayne Rasband's AVI Movie Reader
 * plugin for ImageJ (available at http://rsb.info.nih.gov/ij).
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/AVIReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/AVIReader.java">SVN</a></dd></dl>
 */
public class AVIReader extends FormatReader {

  // -- Constants --

  /** Supported compression types. */
  private static final int MSRLE = 1;
  private static final int MS_VIDEO = 1296126531;
  //private static final int CINEPAK = 1684633187;

  // -- Fields --

  /** Offset to each plane. */
  private Vector offsets;

  /** Number of bytes in each plane. */
  private Vector lengths;

  private String type = "error";
  private String fcc = "error";
  private int size = -1;
  private long pos;

  // Stream Format chunk fields

  private int bmpColorsUsed, bmpWidth;
  private int bmpCompression, bmpScanLineSize;
  private short bmpBitsPerPixel;
  private byte[][] lut = null;

  private byte[] lastImage;
  private int lastImageNo;

  // -- Constructor --

  /** Constructs a new AVI reader. */
  public AVIReader() {
    super("Audio Video Interleave", "avi");
    blockCheckLen = 4;
    suffixNecessary = false;
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessStream) */
  public boolean isThisType(RandomAccessStream stream) throws IOException {
    if (!FormatTools.validStream(stream, blockCheckLen, false)) return false;
    return stream.readString(4).equals("RIFF");
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.assertId(currentId, true, 1);
    FormatTools.checkPlaneNumber(this, no);
    FormatTools.checkBufferSize(this, buf.length, w, h);

    int bytes = FormatTools.getBytesPerPixel(getPixelType());
    double p = ((double) bmpScanLineSize) / bmpBitsPerPixel;
    int effectiveWidth = (int) (bmpScanLineSize / p);
    if (effectiveWidth == 0 || effectiveWidth < getSizeX()) {
      effectiveWidth = getSizeX();
    }

    long fileOff = ((Long) offsets.get(no)).longValue();
    in.seek(fileOff);

    if (bmpCompression != 0) {
      byte[] b = uncompress(no, buf);
      int rowLen = w * bytes * getSizeC();
      for (int row=0; row<h; row++) {
        System.arraycopy(b, (row + y) * getSizeX() * bytes * getSizeC(),
          buf, row * rowLen, rowLen);
      }
      b = null;
      return buf;
    }

    if (bmpBitsPerPixel < 8) {
      int rawSize = bytes * getSizeY() * effectiveWidth * getSizeC();
      rawSize /= (8 / bmpBitsPerPixel);

      byte[] b = new byte[rawSize];

      int len = rawSize / getSizeY();
      in.read(b);

      BitBuffer bb = new BitBuffer(b);
      bb.skipBits(bmpBitsPerPixel * len * (getSizeY() - h - y));

      for (int row=h; row>=y; row--) {
        bb.skipBits(bmpBitsPerPixel * x);
        for (int col=0; col<len; col++) {
          buf[(row - y) * len + col] = (byte) bb.getBits(bmpBitsPerPixel);
        }
        bb.skipBits(bmpBitsPerPixel * (getSizeX() - w - x));
      }

      return buf;
    }

    int pad = (bmpScanLineSize / getRGBChannelCount()) - getSizeX()*bytes;
    int scanline = w * bytes * (isInterleaved() ? getRGBChannelCount() : 1);

    in.skipBytes((getSizeX() + pad) * bytes * (getSizeY() - h - y));

    if (getSizeX() == w && pad == 0) {
      for (int row=0; row<getSizeY(); row++) {
        in.read(buf, (getSizeY() - row - 1) * scanline, scanline);
      }

      // swap channels
      if (bmpBitsPerPixel == 24) {
        for (int i=0; i<buf.length / getRGBChannelCount(); i++) {
          byte r = buf[i * getRGBChannelCount() + 2];
          buf[i * getRGBChannelCount() + 2] = buf[i * getRGBChannelCount()];
          buf[i * getRGBChannelCount()] = r;
        }
      }
    }
    else {
      for (int i=h - 1; i>=0; i--) {
        in.skipBytes(x * (bmpBitsPerPixel / 8));
        in.read(buf, (i - y)*scanline, scanline);
        if (bmpBitsPerPixel == 24) {
          for (int j=0; j<getSizeX(); j++) {
            byte r = buf[i*scanline + j*3 + 2];
            buf[i*scanline + j*3 + 2] = buf[i*scanline + j*3];
            buf[i*scanline + j*3] = r;
          }
        }
        in.skipBytes(bytes * (getSizeX() - w - x + pad) *
          (isInterleaved() ? getRGBChannelCount() : 1));
      }
    }

    if (bmpBitsPerPixel == 16) {
      // channels are separated, need to swap them
      byte[] r = new byte[getSizeX() * getSizeY() * 2];
      System.arraycopy(buf, 2 * (buf.length / 3), r, 0, r.length);
      System.arraycopy(buf, 0, buf, 2 * (buf.length / 3), r.length);
      System.arraycopy(r, 0, buf, 0, r.length);
    }

    return buf;
  }

  // -- IFormatHandler API methods --

  /* @see loci.formats.IFormatHandler#close() */
  public void close() throws IOException {
    super.close();
    offsets = null;
    lengths = null;
    type = null;
    fcc = null;
    size = -1;
    pos = 0;
    bmpColorsUsed = bmpWidth = bmpCompression = bmpScanLineSize = 0;
    bmpBitsPerPixel = 0;
    lut = null;
    lastImage = null;
    lastImageNo = -1;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    if (debug) debug("AVIReader.initFile(" + id + ")");
    super.initFile(id);
    in = new RandomAccessStream(id);
    in.order(true);

    status("Verifying AVI format");

    offsets = new Vector();
    lengths = new Vector();
    lastImageNo = -1;

    String listString;

    type = in.readString(4);
    size = in.readInt();
    fcc = in.readString(4);

    if (type.equals("RIFF")) {
      if (!fcc.equals("AVI ")) {
        throw new FormatException("Sorry, AVI RIFF format not found.");
      }
    }
    else throw new FormatException("Not an AVI file");

    pos = in.getFilePointer();
    long spos = pos;

    status("Searching for image data");

    while ((in.length() - in.getFilePointer()) > 4) {
      listString = in.readString(4);
      in.seek(pos);
      if (listString.equals(" JUN")) {
        in.skipBytes(1);
        pos++;
      }

      if (listString.equals("JUNK")) {
        type = in.readString(4);
        size = in.readInt();

        if (type.equals("JUNK")) {
          in.skipBytes(size);
        }
      }
      else if (listString.equals("LIST")) {
        spos = in.getFilePointer();
        type = in.readString(4);
        size = in.readInt();
        fcc = in.readString(4);

        in.seek(spos);
        if (fcc.equals("hdrl")) {
          type = in.readString(4);
          size = in.readInt();
          fcc = in.readString(4);

          if (type.equals("LIST")) {
            if (fcc.equals("hdrl")) {
              type = in.readString(4);
              size = in.readInt();
              if (type.equals("avih")) {
                spos = in.getFilePointer();

                addMeta("Microseconds per frame", in.readInt());
                addMeta("Max. bytes per second", in.readInt());

                in.skipBytes(8);

                addMeta("Total frames", in.readInt());
                addMeta("Initial frames", in.readInt());

                in.skipBytes(8);
                core[0].sizeX = in.readInt();

                addMeta("Frame height", in.readInt());
                addMeta("Scale factor", in.readInt());
                addMeta("Frame rate", in.readInt());
                addMeta("Start time", in.readInt());
                addMeta("Length", in.readInt());

                addMeta("Frame width", getSizeX());

                if (spos + size <= in.length()) {
                  in.seek(spos + size);
                }
              }
            }
          }
        }
        else if (fcc.equals("strl")) {
          long startPos = in.getFilePointer();
          long streamSize = size;

          type = in.readString(4);
          size = in.readInt();
          fcc = in.readString(4);

          if (type.equals("LIST")) {
            if (fcc.equals("strl")) {
              type = in.readString(4);
              size = in.readInt();

              if (type.equals("strh")) {
                spos = in.getFilePointer();
                in.skipBytes(40);

                addMeta("Stream quality", in.readInt());
                addMeta("Stream sample size", in.readInt());

                if (spos + size <= in.length()) {
                  in.seek(spos + size);
                }
              }

              type = in.readString(4);
              size = in.readInt();
              if (type.equals("strf")) {
                spos = in.getFilePointer();

                in.skipBytes(4);
                bmpWidth = in.readInt();
                core[0].sizeY = in.readInt();
                in.skipBytes(2);
                bmpBitsPerPixel = in.readShort();
                bmpCompression = in.readInt();
                in.skipBytes(4);

                addMeta("Horizontal resolution", in.readInt());
                addMeta("Vertical resolution", in.readInt());

                bmpColorsUsed = in.readInt();
                in.skipBytes(4);

                addMeta("Bitmap compression value", bmpCompression);
                addMeta("Number of colors used", bmpColorsUsed);
                addMeta("Bits per pixel", bmpBitsPerPixel);

                // scan line is padded with zeros to be a multiple of 4 bytes
                int npad = bmpWidth % 4;
                if (npad > 0) npad = 4 - npad;

                bmpScanLineSize = (bmpWidth + npad) * (bmpBitsPerPixel / 8);

                int bmpActualColorsUsed = 0;
                if (bmpColorsUsed != 0) {
                  bmpActualColorsUsed = bmpColorsUsed;
                }
                else if (bmpBitsPerPixel < 16) {
                  // a value of 0 means we determine this based on the
                  // bits per pixel
                  bmpActualColorsUsed = 1 << bmpBitsPerPixel;
                  bmpColorsUsed = bmpActualColorsUsed;
                }

                if (bmpCompression != MSRLE && bmpCompression != 0 &&
                  bmpCompression != MS_VIDEO/* && bmpCompression != CINEPAK*/)
                {
                  throw new FormatException("Unsupported compression type " +
                    bmpCompression);
                }

                if (!(bmpBitsPerPixel == 4 || bmpBitsPerPixel == 8 ||
                  bmpBitsPerPixel == 24 || bmpBitsPerPixel == 16 ||
                  bmpBitsPerPixel == 32))
                {
                  throw new FormatException(bmpBitsPerPixel +
                    " bits per pixel not supported");
                }

                if (bmpActualColorsUsed != 0) {
                  // read the palette
                  lut = new byte[3][bmpColorsUsed];

                  for (int i=0; i<bmpColorsUsed; i++) {
                    lut[2][i] = in.readByte();
                    lut[1][i] = in.readByte();
                    lut[0][i] = in.readByte();
                    in.skipBytes(1);
                  }
                }

                in.seek(spos + size);
              }
            }

            spos = in.getFilePointer();
            type = in.readString(4);
            size = in.readInt();
            if (type.equals("strd")) {
              in.skipBytes(size);
            }
            else {
              in.seek(spos);
            }

            spos = in.getFilePointer();
            type = in.readString(4);
            size = in.readInt();
            if (type.equals("strn")) {
              in.skipBytes(size);
            }
            else {
              in.seek(spos);
            }
          }

          if (startPos + streamSize + 8 <= in.length()) {
            in.seek(startPos + 8 + streamSize);
          }
        }
        else if (fcc.equals("movi")) {
          type = in.readString(4);
          size = in.readInt();
          fcc = in.readString(4);

          if (type.equals("LIST")) {
            if (fcc.equals("movi")) {
              spos = in.getFilePointer();
              if (spos >= in.length() - 12) break;
              type = in.readString(4);
              size = in.readInt();
              fcc = in.readString(4);
              if (!(type.equals("LIST") && (fcc.equals("rec ") ||
                fcc.equals("movi"))))
              {
                in.seek(spos);
              }

              spos = in.getFilePointer();
              type = in.readString(4);
              if (type.startsWith("ix")) {
                size = in.readInt();
                in.skipBytes(size);
                type = in.readString(4);
                size = in.readInt();
              }
              else {
                size = in.readInt();
              }

              while (type.substring(2).equals("db") ||
                type.substring(2).equals("dc") ||
                type.substring(2).equals("wb"))
              {
                if (type.substring(2).equals("db") ||
                  type.substring(2).equals("dc"))
                {
                  offsets.add(new Long(in.getFilePointer()));
                  lengths.add(new Long(size));
                  in.skipBytes(size);
                }

                spos = in.getFilePointer();

                type = in.readString(4);
                size = in.readInt();
                if (type.equals("JUNK")) {
                  in.skipBytes(size);
                  spos = in.getFilePointer();
                  type = in.readString(4);
                  size = in.readInt();
                }
              }
              in.seek(spos);
            }
          }
        }
        else {
          // skipping unknown block
          try {
            in.skipBytes(8 + size);
          }
          catch (IllegalArgumentException iae) { }
        }
      }
      else {
        // skipping unknown block
        type = in.readString(4);
        if (in.getFilePointer() + size + 4 <= in.length()) {
          size = in.readInt();
          in.skipBytes(size);
        }
      }
      pos = in.getFilePointer();
    }
    status("Populating metadata");

    core[0].imageCount = offsets.size();

    core[0].rgb = bmpBitsPerPixel > 8 || (bmpCompression != 0);
    core[0].indexed = false;
    core[0].sizeZ = 1;
    core[0].sizeT = getImageCount();
    core[0].littleEndian = true;
    core[0].interleaved = bmpBitsPerPixel != 16;
    core[0].sizeC = isRGB() ? 3 : 1;
    core[0].dimensionOrder = getSizeC() == 3 ? "XYCTZ" : "XYTCZ";
    core[0].falseColor = false;
    core[0].metadataComplete = true;

    if (bmpBitsPerPixel <= 8) core[0].pixelType = FormatTools.UINT8;
    else if (bmpBitsPerPixel == 16) core[0].pixelType = FormatTools.UINT16;
    else if (bmpBitsPerPixel == 32) core[0].pixelType = FormatTools.UINT32;
    else if (bmpBitsPerPixel == 24) core[0].pixelType = FormatTools.UINT8;
    else {
      throw new FormatException(
          "Unknown matching for pixel bit width of: " + bmpBitsPerPixel);
    }

    if (bmpCompression != 0) core[0].pixelType = FormatTools.UINT8;

    MetadataStore store =
      new FilterMetadata(getMetadataStore(), isMetadataFiltered());
    store.setImageName("", 0);
    MetadataTools.setDefaultCreationDate(store, id, 0);
    MetadataTools.populatePixels(store, this);
  }

  // -- Helper methods --

  private byte[] uncompress(int no, byte[] buf)
    throws FormatException, IOException
  {
    byte[] b = new byte[(int) ((Long) lengths.get(no)).longValue()];
    in.read(b);
    if (bmpCompression == MSRLE) {
      Object[] options = new Object[2];
      options[1] = (lastImageNo == no - 1) ? lastImage : null;
      options[0] = new int[] {getSizeX(), getSizeY()};
      MSRLECodec codec = new MSRLECodec();
      buf = codec.decompress(b, options);
      lastImage = buf;
      lastImageNo = no;
    }
    else if (bmpCompression == MS_VIDEO) {
      Object[] options = new Object[4];
      options[0] = new Integer(bmpBitsPerPixel);
      options[1] = new Integer(getSizeX());
      options[2] = new Integer(getSizeY());
      options[3] = (lastImageNo == no - 1) ? lastImage : null;

      MSVideoCodec codec = new MSVideoCodec();
      buf = codec.decompress(b, options);
      lastImage = buf;
      lastImageNo = no;
    }
    /*
    else if (bmpCompression == CINEPAK) {
      Object[] options = new Object[2];
      options[0] = new Integer(bmpBitsPerPixel);
      options[1] = lastImage;

      CinepakCodec codec = new CinepakCodec();
      buf = codec.decompress(b, options);
      lastImage = buf;
      if (no == core[0].imageCount - 1) lastImage = null;
      return buf;
    }
    */
    else {
      throw new FormatException("Unsupported compression : " + bmpCompression);
    }
    if (lut != null) {
      b = buf;
      buf = new byte[b.length * getSizeC()];
      for (int i=0; i<b.length; i++) {
        buf[i*getSizeC()] = lut[0][b[i] & 0xff];
        buf[i*getSizeC() + 1] = lut[1][b[i] & 0xff];
        buf[i*getSizeC() + 2] = lut[2][b[i] & 0xff];
      }
    }
    return buf;
  }

}
