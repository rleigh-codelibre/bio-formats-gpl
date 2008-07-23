//
// MetamorphTiffReader.java
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
import java.text.*;
import java.util.*;
import loci.formats.*;
import loci.formats.meta.FilterMetadata;
import loci.formats.meta.MetadataStore;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * MetamorphTiffReader is the file format reader for TIFF files produced by
 * Metamorph software version 7.5 and above.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/loci/formats/in/MetamorphTiffReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/loci/formats/in/MetamorphTiffReader.java">SVN</a></dd></dl>
 *
 * @author Melissa Linkert linkert at wisc.edu
 * @author Thomas Caswell tac42 at cornell.edu
 */
public class MetamorphTiffReader extends BaseTiffReader {

  // -- Fields --

  private float pixelSizeX, pixelSizeY;
  private float[] zPositions;
  private int[] wavelengths;
  private int zpPointer, wavePointer;
  private float temperature;
  private String date, imageName;

  // -- Constructor --

  /** Constructs a new Metamorph TIFF reader. */
  public MetamorphTiffReader() {
    super("Metamorph TIFF", new String[] {"tif", "tiff"});
    blockCheckLen = 524288;
    suffixSufficient = false;
  }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(String, boolean) */
  public boolean isThisType(String name, boolean open) {
    if (!open) return false;
    try {
      RandomAccessStream stream = new RandomAccessStream(name);
      boolean isThisType = isThisType(stream);
      stream.close();
      return isThisType;
    }
    catch (IOException e) {
      if (debug) trace(e);
    }
    return false;
  }

  /* @see loci.formats.IFormatReader#isThisType(RandomAccessStream) */
  public boolean isThisType(RandomAccessStream stream) throws IOException {
    if (!FormatTools.validStream(stream, blockCheckLen, false)) return false;
    String comment = TiffTools.getComment(TiffTools.getFirstIFD(stream));
    return comment != null && comment.trim().startsWith("<MetaData>");
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    if (debug) debug("MetamorphTiffReader.initFile(" + id + ")");
    super.initFile(id);

    String[] comments = new String[ifds.length];
    zPositions = new float[ifds.length];
    wavelengths = new int[ifds.length];
    zpPointer = 0;
    wavePointer = 0;

    // parse XML comment

    DefaultHandler handler = new MetamorphHandler();
    for (int i=0; i<comments.length; i++) {
      comments[i] = TiffTools.getComment(ifds[i]);
      DataTools.parseXML(comments[i], handler);
    }

    core.sizeC[0] = core.sizeZ[0] = 0;

    // calculate axis sizes

    Vector uniqueZ = new Vector();
    Vector uniqueC = new Vector();
    for (int i=0; i<zPositions.length; i++) {
      Float z = new Float(zPositions[i]);
      Integer c = new Integer(wavelengths[i]);
      if (!uniqueZ.contains(z)) {
        uniqueZ.add(z);
        core.sizeZ[0]++;
      }
      if (!uniqueC.contains(c)) {
        uniqueC.add(c);
        core.sizeC[0]++;
      }
    }

    core.sizeT[0] = ifds.length / (getSizeZ() * getSizeC());

    MetadataStore store =
      new FilterMetadata(getMetadataStore(), isMetadataFiltered());
    store.setImageName(imageName, 0);
    store.setImageDescription("", 0);

    SimpleDateFormat parse = new SimpleDateFormat("yyyyMMdd HH:mm:ss.SS");
    Date d = parse.parse(date, new ParsePosition(0));
    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

    store.setImageCreationDate(fmt.format(d), 0);
    MetadataTools.populatePixels(store, this);

    store.setImagingEnvironmentTemperature(new Float(temperature), 0);
    store.setDimensionsPhysicalSizeX(new Float(pixelSizeX), 0, 0);
    store.setDimensionsPhysicalSizeY(new Float(pixelSizeY), 0, 0);
  }

  // -- Helper class --

  /** SAX handler for parsing XML. */
  class MetamorphHandler extends DefaultHandler {
    public void startElement(String uri, String localName, String qName,
      Attributes attributes)
    {
      String id = attributes.getValue("id");
      String value = attributes.getValue("value");
      if (id != null && value != null) {
        if (id.equals("Description")) {
          metadata.remove("Comment");

          String k = null, v = null;

          if (value.indexOf("&#13;&#10;") != -1) {
            StringTokenizer tokens = new StringTokenizer(value, "&#130;&#10;");
            while (tokens.hasMoreTokens()) {
              String line = tokens.nextToken();
              int colon = line.indexOf(":");
              if (colon != -1) {
                k = line.substring(0, colon).trim();
                v = line.substring(colon + 1).trim();
                addMeta(k, v);
                if (k.equals("Temperature")) {
                  temperature = Float.parseFloat(v.trim());
                }
              }
            }
          }
          else {
            int colon = value.indexOf(":");
            while (colon != -1) {
              k = value.substring(0, colon);
              int space = value.lastIndexOf(" ", value.indexOf(":", colon + 1));
              if (space == -1) space = value.length();
              v = value.substring(colon + 1, space);
              addMeta(k, v);
              value = value.substring(space).trim();
              colon = value.indexOf(":");

              if (k.equals("Temperature")) {
                temperature = Float.parseFloat(v.trim());
              }
            }
          }
        }
        else addMeta(id, value);

        if (id.equals("spatial-calibration-x")) {
          pixelSizeX = Float.parseFloat(value);
        }
        else if (id.equals("spatial-calibration-y")) {
          pixelSizeY = Float.parseFloat(value);
        }
        else if (id.equals("z-position")) {
          zPositions[zpPointer++] = Float.parseFloat(value);
        }
        else if (id.equals("wavelength")) {
          wavelengths[wavePointer++] = Integer.parseInt(value);
        }
        else if (id.equals("acquisition-time-local")) date = value;
        else if (id.equals("image-name")) imageName = value;
      }
    }
  }

}
