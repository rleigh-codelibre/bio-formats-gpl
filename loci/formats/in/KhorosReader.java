//
// KhorosReader.java
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

import java.io.*;
import loci.formats.*;
import loci.formats.meta.FilterMetadata;
import loci.formats.meta.MetadataStore;

/**
 * Reader for Khoros XV files.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/loci/formats/in/KhorosReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/loci/formats/in/KhorosReader.java">SVN</a></dd></dl>
 */
public class KhorosReader extends FormatReader {

  // -- Fields --

  /** Global lookup table. */
  private byte[] lut;

  /** Image offset. */
  private long offset;

  // -- Constructor --

  /** Constructs a new Khoros reader. */
  public KhorosReader() { super("Khoros XV", "xv"); }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(byte[]) */
  public boolean isThisType(byte[] block) {
    return block[0] == (byte) 0xab && block[1] == 1;
  }

  /* @see loci.formats.IFormatReader#get8BitLookupTable() */
  public byte[][] get8BitLookupTable() throws FormatException, IOException {
    FormatTools.assertId(currentId, true, 1);
    if (lut == null) return null;
    byte[][] table = new byte[3][lut.length / 3];
    int next = 0;
    for (int i=0; i<table[0].length; i++) {
      for (int j=0; j<table.length; j++) {
        table[j][i] = lut[next++];
      }
    }
    return table;
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

    int bytes = FormatTools.getBytesPerPixel(core.pixelType[0]);
    int bufSize = core.sizeX[0] * core.sizeY[0] * bytes;

    in.seek(offset + no * bufSize + y * core.sizeX[0] * bytes);

    for (int row=0; row<h; row++) {
      in.skipBytes(x * bytes);
      in.read(buf, row * w * bytes, w * bytes);
      in.skipBytes(bytes * (core.sizeX[0] - w - x));
    }

    return buf;
  }

  // -- IFormatHandler API methods --

  /* @see loci.formats.IFormatHandler#close() */
  public void close() throws IOException {
    super.close();
    lut = null;
    offset = 0;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    super.initFile(id);
    in = new RandomAccessStream(id);

    in.skipBytes(4);
    in.order(true);
    int dependency = in.readInt();

    addMeta("Comment", in.readString(512));

    in.order(dependency == 4 || dependency == 8);

    core.sizeX[0] = in.readInt();
    core.sizeY[0] = in.readInt();
    in.skipBytes(28);
    core.imageCount[0] = in.readInt();
    if (core.imageCount[0] == 0) core.imageCount[0] = 1;
    core.sizeC[0] = in.readInt();

    int type = in.readInt();

    switch (type) {
      case 0:
        core.pixelType[0] = FormatTools.INT8;
        break;
      case 1:
        core.pixelType[0] = FormatTools.UINT8;
        break;
      case 2:
        core.pixelType[0] = FormatTools.UINT16;
        break;
      case 4:
        core.pixelType[0] = FormatTools.UINT32;
        break;
      case 5:
        core.pixelType[0] = FormatTools.FLOAT;
        break;
      case 9:
        core.pixelType[0] = FormatTools.DOUBLE;
        break;
      default: throw new FormatException("Unsupported pixel type : " + type);
    }

    in.skipBytes(12);
    int c = in.readInt();
    if (c > 1) {
      core.sizeC[0] = c;
      int n = in.readInt();
      lut = new byte[n * c];
      in.skipBytes(436);

      for (int i=0; i<lut.length; i++) {
        int value = in.read();
        if (i < n) {
          lut[i*3] = (byte) value;
          lut[i*3 + 1] = (byte) value;
          lut[i*3 + 2] = (byte) value;
        }
        else if (i < n*2) {
          lut[(i % n)*3 + 1] = (byte) value;
        }
        else if (i < n*3) {
          lut[(i % n)*3 + 2] = (byte) value;
        }
      }
    }
    else in.skipBytes(440);
    offset = in.getFilePointer();

    core.sizeZ[0] = core.imageCount[0];
    core.sizeT[0] = 1;
    core.rgb[0] = core.sizeC[0] > 1;
    core.interleaved[0] = false;
    core.littleEndian[0] = dependency == 4 || dependency == 8;
    core.currentOrder[0] = "XYCZT";
    core.indexed[0] = lut != null;
    core.falseColor[0] = false;
    core.metadataComplete[0] = true;

    MetadataStore store =
      new FilterMetadata(getMetadataStore(), isMetadataFiltered());
    store.setImageName("", 0);
    store.setImageCreationDate(
      DataTools.convertDate(System.currentTimeMillis(), DataTools.UNIX), 0);
    MetadataTools.populatePixels(store, this);
  }

}
