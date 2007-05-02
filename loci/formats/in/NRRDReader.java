//
// NRRDReader.java
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
import java.util.StringTokenizer;
import loci.formats.*;

/** File format reader for NRRD files;  see http://teem.sourceforge.net/nrrd. */
public class NRRDReader extends FormatReader {

  // -- Fields --

  /** Helper reader. */
  private ImageReader helper;

  /** Name of data file, if the current extension is 'nhdr'. */
  private String dataFile;

  /** Data encoding. */
  private String encoding;

  /** The min and max values, if present. */
  private String min, max;

  /** Offset to pixel data. */
  private long offset;

  // -- Constructor --

  /** Constructs a new NRRD reader. */
  public NRRDReader() { super("NRRD", new String[] {"nrrd", "nhdr"}); }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(byte[]) */
  public boolean isThisType(byte[] block) {
    if (block.length < 4) return false;
    return block[0] == 'N' && block[1] == 'R' && block[2] == 'R' &&
      block[3] == 'D';
  }

  /* @see loci.formats.IFormatReader#openBytes(int) */
  public byte[] openBytes(int no) throws FormatException, IOException {
    FormatTools.assertId(currentId, true, 1);
    byte[] buf = new byte[core.sizeX[0] * core.sizeY[0] * core.sizeC[0] *
      FormatTools.getBytesPerPixel(core.pixelType[0])];
    return openBytes(no, buf);
  }

  /* @see loci.formats.IFormatReader#openBytes(int, byte[]) */
  public byte[] openBytes(int no, byte[] buf)
    throws FormatException, IOException
  {
    FormatTools.assertId(currentId, true, 1);
    if (no < 0 || no >= core.imageCount[0]) {
      throw new FormatException("Invalid image number: " + no);
    }

    if (buf.length < core.sizeX[0] * core.sizeY[0] * core.sizeC[0] *
      FormatTools.getBytesPerPixel(core.pixelType[0]))
    {
      throw new FormatException("Buffer too small.");
    }

    // TODO : add support for additional encoding types
    if (dataFile == null) {
      if (encoding.equals("raw")) {
        in.seek(offset + no * buf.length);
        in.read(buf);
        return buf;
      }
      else throw new FormatException("Unsupported encoding: " + encoding);
    }
    return helper.openBytes(no, buf);
  }

  /* @see loci.formats.IFormatReader#openImage(int) */
  public BufferedImage openImage(int no) throws FormatException, IOException {
    FormatTools.assertId(currentId, true, 1);
    if (core.pixelType[0] == FormatTools.FLOAT) {
      byte[] b = openBytes(no);
      float[] f = new float[core.sizeX[0] * core.sizeY[0] * core.sizeC[0]];
      for (int i=0; i<f.length; i++) {
        f[i] = Float.intBitsToFloat(
          DataTools.bytesToInt(b, i*4, 4, core.littleEndian[0]));
      }
      if (normalizeData) f = DataTools.normalizeFloats(f);
      return ImageTools.makeImage(f, core.sizeX[0], core.sizeY[0],
        core.sizeC[0], core.interleaved[0]);
    }

    return ImageTools.makeImage(openBytes(no), core.sizeX[0], core.sizeY[0],
      core.sizeC[0], core.interleaved[0],
      FormatTools.getBytesPerPixel(core.pixelType[0]), core.littleEndian[0]);
  }

  /* @see loci.formats.IFormatReader#close() */
  public void close() throws IOException {
    super.close();
    if (helper != null) helper.close();
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    super.initFile(id);
    in = new RandomAccessStream(id);
    helper = new ImageReader();

    boolean finished = false;
    String line, key, v;

    int numDimensions = 0;

    core.sizeX[0] = 1;
    core.sizeY[0] = 1;
    core.sizeZ[0] = 1;
    core.sizeC[0] = 1;
    core.sizeT[0] = 1;
    core.currentOrder[0] = "XYCZT";

    while (!finished) {
      line = in.readLine().trim();
      if (!line.startsWith("#") && line.length() > 0 &&
        !line.startsWith("NRRD"))
      {
        // parse key/value pair
        key = line.substring(0, line.indexOf(":")).trim();
        v = line.substring(line.indexOf(":") + 1).trim();
        addMeta(key, v);

        if (key.equals("type")) {
          if (v.indexOf("char") != -1 || v.indexOf("8") != -1) {
            core.pixelType[0] = FormatTools.UINT8;
          }
          else if (v.indexOf("short") != -1 || v.indexOf("16") != -1) {
            core.pixelType[0] = FormatTools.UINT16;
          }
          else if (v.equals("int") || v.equals("signed int") ||
            v.equals("int32") || v.equals("int32_t") || v.equals("uint") ||
            v.equals("unsigned int") || v.equals("uint32") ||
            v.equals("uint32_t"))
          {
            core.pixelType[0] = FormatTools.UINT32;
          }
          else if (v.equals("float")) core.pixelType[0] = FormatTools.FLOAT;
          else if (v.equals("double")) core.pixelType[0] = FormatTools.DOUBLE;
          else throw new FormatException("Unsupported data type: " + v);
        }
        else if (key.equals("dimension")) {
          numDimensions = Integer.parseInt(v);
        }
        else if (key.equals("sizes")) {
          StringTokenizer tokens = new StringTokenizer(v, " ");
          for (int i=0; i<numDimensions; i++) {
            String t = tokens.nextToken();
            int size = Integer.parseInt(t);

            if (numDimensions >= 3 && i == 0 && size > 1 && size <= 4) {
              core.sizeC[0] = size;
            }
            else if (i == 0 || (core.sizeC[0] > 1 && i == 1)) {
              core.sizeX[0] = size;
            }
            else if (i == 1 || (core.sizeC[0] > 1 && i == 2)) {
              core.sizeY[0] = size;
            }
            else if (i == 2 || (core.sizeC[0] > 1 && i == 3)) {
              core.sizeZ[0] = size;
            }
            else if (i == 3 || (core.sizeC[0] > 1 && i == 4)) {
              core.sizeT[0] = size;
            }
          }
        }
        else if (key.equals("data file") || key.equals("datafile")) {
          dataFile = v;
        }
        else if (key.equals("encoding")) encoding = v;
        else if (key.equals("endian")) {
          core.littleEndian[0] = v.equals("little");
        }
        else if (key.equals("min")) min = v;
        else if (key.equals("max")) max = v;
      }

      if ((line.length() == 0 && dataFile == null) || line == null) {
        finished = true;
      }
      if (dataFile != null && (in.length() - in.getFilePointer() < 2)) {
        finished = true;
      }
    }

    if (dataFile == null) offset = in.getFilePointer();
    else helper.setId(dataFile);

    core.rgb[0] = core.sizeC[0] > 1;
    core.interleaved[0] = true;
    core.imageCount[0] = core.sizeZ[0] * core.sizeT[0];

    MetadataStore store = getMetadataStore();

    store.setPixels(new Integer(core.sizeX[0]), new Integer(core.sizeY[0]),
      new Integer(core.sizeZ[0]), new Integer(core.sizeC[0]),
      new Integer(core.sizeT[0]), new Integer(core.pixelType[0]),
      new Boolean(core.littleEndian[0]), core.currentOrder[0], null, null);
  }

}
