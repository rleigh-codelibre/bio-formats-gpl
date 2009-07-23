//
// OpenlabRawReader.java
//

/*
OME Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ UW-Madison LOCI and Glencoe Software, Inc.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
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

import java.io.IOException;

import loci.common.DateTools;
import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.FilterMetadata;
import loci.formats.meta.MetadataStore;

/**
 * OpenlabRawReader is the file format reader for Openlab RAW files.
 * Specifications available at
 * http://www.improvision.com/support/tech_notes/detail.php?id=344
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/OpenlabRawReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/OpenlabRawReader.java">SVN</a></dd></dl>
 *
 * @author Melissa Linkert linkert at wisc.edu
 */
public class OpenlabRawReader extends FormatReader {

  // -- Constants --

  public static final String OPENLAB_RAW_MAGIC_STRING = "OLRW";

  // -- Fields --

  /** Offset to each image's pixel data. */
  protected int[] offsets;

  /** Number of bytes per pixel. */
  private int bytesPerPixel;

  // -- Constructor --

  /** Constructs a new RAW reader. */
  public OpenlabRawReader() {
    super("Openlab RAW", "raw");
    suffixSufficient = false;
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException {
    final int blockLen = OPENLAB_RAW_MAGIC_STRING.length();
    if (!FormatTools.validStream(stream, blockLen, false)) return false;
    return stream.readString(blockLen).startsWith(OPENLAB_RAW_MAGIC_STRING);
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    in.seek(offsets[no / getSizeC()] + 288);
    readPlane(in, x, y, w, h, buf);

    if (FormatTools.getBytesPerPixel(getPixelType()) == 1) {
      // need to invert the pixels
      for (int i=0; i<buf.length; i++) {
        buf[i] = (byte) (255 - buf[i]);
      }
    }
    return buf;
  }

  // -- IFormatHandler API methods --

  /* @see loci.formats.IFormatHandler#close() */
  public void close() throws IOException {
    super.close();
    offsets = null;
    bytesPerPixel = 0;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    debug("OpenlabRawReader.initFile(" + id + ")");
    super.initFile(id);
    in = new RandomAccessInputStream(id);

    // read the 12 byte file header

    status("Verifying Openlab RAW format");

    if (!in.readString(4).equals("OLRW")) {
      throw new FormatException("Openlab RAW magic string not found.");
    }

    status("Populating metadata");

    addGlobalMeta("Version", in.readInt());

    core[0].imageCount = in.readInt();
    offsets = new int[getImageCount()];
    offsets[0] = 12;

    in.skipBytes(8);
    core[0].sizeX = in.readInt();
    core[0].sizeY = in.readInt();
    in.skipBytes(1);
    core[0].sizeC = in.read();
    bytesPerPixel = in.read();
    in.skipBytes(1);

    long stampMs = in.readLong();
    if (stampMs > 0) {
      stampMs /= 1000000;
      stampMs -= (67 * 365.25 * 24 * 60 * 60);
    }
    else stampMs = System.currentTimeMillis();

    String stamp = DateTools.convertDate(stampMs, DateTools.UNIX);
    addGlobalMeta("Timestamp", stamp);

    in.skipBytes(4);
    int len = in.read() & 0xff;
    addGlobalMeta("Image name", in.readString(len - 1).trim());

    if (getSizeC() <= 1) core[0].sizeC = 1;
    else core[0].sizeC = 3;
    addGlobalMeta("Width", getSizeX());
    addGlobalMeta("Height", getSizeY());
    addGlobalMeta("Bytes per pixel", bytesPerPixel);

    int plane = getSizeX() * getSizeY() * bytesPerPixel;
    for (int i=1; i<getImageCount(); i++) {
      offsets[i] = offsets[i - 1] + 288 + plane;
    }

    core[0].sizeZ = getImageCount();
    core[0].sizeT = 1;
    core[0].dimensionOrder = "XYZTC";
    core[0].rgb = getSizeC() > 1;
    core[0].interleaved = false;
    core[0].littleEndian = false;
    core[0].metadataComplete = true;
    core[0].indexed = false;
    core[0].falseColor = false;

    switch (bytesPerPixel) {
      case 1:
      case 3:
        core[0].pixelType = FormatTools.UINT8;
        break;
      case 2:
        core[0].pixelType = FormatTools.UINT16;
        break;
      default:
        core[0].pixelType = FormatTools.FLOAT;
    }

    // The metadata store we're working with.
    MetadataStore store =
      new FilterMetadata(getMetadataStore(), isMetadataFiltered());
    MetadataTools.populatePixels(store, this);
    store.setImageCreationDate(stamp, 0);
  }

}
