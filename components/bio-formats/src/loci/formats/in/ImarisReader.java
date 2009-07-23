//
// ImarisReader.java
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

import loci.common.RandomAccessInputStream;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.FilterMetadata;
import loci.formats.meta.MetadataStore;

/**
 * ImarisReader is the file format reader for Bitplane Imaris files.
 * Specifications available at
 * http://flash.bitplane.com/support/faqs/faqsview.cfm?inCat=6&inQuestionID=104
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/ImarisReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/ImarisReader.java">SVN</a></dd></dl>
 *
 * @author Melissa Linkert linkert at wisc.edu
 */
public class ImarisReader extends FormatReader {

  // -- Constants --

  /** Magic number; present in all files. */
  private static final int IMARIS_MAGIC_BYTES = 5021964;

  /** Specifies endianness. */
  private static final boolean IS_LITTLE = false;

  // -- Fields --

  /** Offsets to each image. */
  private int[] offsets;

  // -- Constructor --

  /** Constructs a new Imaris reader. */
  public ImarisReader() {
    super("Bitplane Imaris", "ims");
    suffixSufficient = false;
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessInputStream) */
  public boolean isThisType(RandomAccessInputStream stream) throws IOException {
    final int blockLen = 4;
    if (!FormatTools.validStream(stream, blockLen, IS_LITTLE)) {
      return false;
    }
    return stream.readInt() == IMARIS_MAGIC_BYTES;
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    in.seek(offsets[no] + getSizeX() * (getSizeY() - y - h));

    for (int row=h-1; row>=0; row--) {
      in.skipBytes(x);
      in.read(buf, row*w, w);
      in.skipBytes(getSizeX() - w - x);
    }
    return buf;
  }

  // -- IFormatHandler API methods --

  /* @see loci.formats.IFormatHandler#close() */
  public void close() throws IOException {
    super.close();
    offsets = null;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    debug("ImarisReader.initFile(" + id + ")");
    super.initFile(id);
    in = new RandomAccessInputStream(id);

    status("Verifying Imaris RAW format");

    in.order(IS_LITTLE);

    long magic = in.readInt();
    if (magic != IMARIS_MAGIC_BYTES) {
      throw new FormatException("Imaris magic number not found.");
    }

    status("Reading header");

    addGlobalMeta("Version", in.readInt());
    in.skipBytes(4);

    addGlobalMeta("Image name", in.readString(128));

    core[0].sizeX = in.readShort();
    core[0].sizeY = in.readShort();
    core[0].sizeZ = in.readShort();

    in.skipBytes(2);

    core[0].sizeC = in.readInt();
    in.skipBytes(2);

    addGlobalMeta("Original date", in.readString(32));

    float dx = in.readFloat();
    float dy = in.readFloat();
    float dz = in.readFloat();
    int mag = in.readShort();

    addGlobalMeta("Image comment", in.readString(128));
    int isSurvey = in.readInt();
    addGlobalMeta("Survey performed", isSurvey == 0);

    status("Calculating image offsets");

    core[0].imageCount = getSizeZ() * getSizeC();
    offsets = new int[getImageCount()];

    float[] gains = new float[getSizeC()];
    float[] detectorOffsets = new float[getSizeC()];
    float[] pinholes = new float[getSizeC()];

    for (int i=0; i<getSizeC(); i++) {
      addGlobalMeta("Channel #" + i + " Comment", in.readString(128));
      gains[i] = in.readFloat();
      detectorOffsets[i] = in.readFloat();
      pinholes[i] = in.readFloat();
      in.skipBytes(24);
      int offset = 336 + (164 * getSizeC()) +
        (i * getSizeX() * getSizeY() * getSizeZ());
      for (int j=0; j<getSizeZ(); j++) {
        offsets[i*getSizeZ() + j] = offset + (j * getSizeX() * getSizeY());
      }
    }

    status("Populating metadata");

    core[0].sizeT = getImageCount() / (getSizeC() * getSizeZ());
    core[0].dimensionOrder = "XYZCT";
    core[0].rgb = false;
    core[0].interleaved = false;
    core[0].littleEndian = IS_LITTLE;
    core[0].indexed = false;
    core[0].falseColor = false;
    core[0].metadataComplete = true;
    core[0].pixelType = FormatTools.UINT8;

    // The metadata store we're working with.
    MetadataStore store =
      new FilterMetadata(getMetadataStore(), isMetadataFiltered());
    MetadataTools.populatePixels(store, this);

    // populate Image data

    MetadataTools.setDefaultCreationDate(store, id, 0);

    // link Instrument and Image
    store.setInstrumentID("Instrument:0", 0);
    store.setImageInstrumentRef("Instrument:0", 0);

    // populate Dimensions data

    store.setDimensionsPhysicalSizeX(new Float(dx), 0, 0);
    store.setDimensionsPhysicalSizeY(new Float(dy), 0, 0);
    store.setDimensionsPhysicalSizeZ(new Float(dz), 0, 0);
    store.setDimensionsTimeIncrement(new Float(1), 0, 0);
    store.setDimensionsWaveIncrement(new Integer(1), 0, 0);

    // populate LogicalChannel data

    for (int i=0; i<getSizeC(); i++) {
      if (pinholes[i] > 0) {
        store.setLogicalChannelPinholeSize(new Float(pinholes[i]), 0, i);
      }
    }

    // populate Detector data

    for (int i=0; i<getSizeC(); i++) {
      if (gains[i] > 0) {
        store.setDetectorSettingsGain(new Float(gains[i]), 0, i);
      }
      store.setDetectorSettingsOffset(new Float(offsets[i]), i, 0);

      // link DetectorSettings to an actual Detector
      store.setDetectorID("Detector:" + i, 0, i);
      store.setDetectorSettingsDetector("Detector:" + i, 0, i);
    }

    // CTR CHECK
    //store.setObjectiveCalibratedMagnification(new Float(mag), 0, 0);
  }

}
