//
// OIFReader.java
//

/*
LOCI Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-2006 Melissa Linkert, Curtis Rueden and Eric Kjellman.

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

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.Hashtable;
import java.util.Vector;

/**
 * OIFReader is the file format reader for Fluoview FV 1000 OIF files.
 *
 * @author Melissa Linkert linkert at cs.wisc.edu
 */
public class OIFReader extends FormatReader {

  // -- Fields --

  /** Current file. */
  protected BufferedReader reader;

  /** Number of image planes in the file. */
  protected int numImages = 0;

  /** Names of every TIFF file to open. */
  protected Vector tiffs;

  /** Helper reader to open TIFF files. */
  protected TiffReader tiffReader;


  // -- Constructor --

  /** Constructs a new OIF reader. */
  public OIFReader() {
    super("Fluoview FV1000 OIF",
      new String[] {"oif", "roi", "pty", "lut", "bmp"});
  }


  // -- FormatReader API methods --

  /** Checks if the given block is a valid header for an OIF file. */
  public boolean isThisType(byte[] block) {
    return false;
  }

  /** Determines the number of images in the given OIF file. */
  public int getImageCount(String id) throws FormatException, IOException {
    if (!id.equals(currentId)) initFile(id);
    return numImages;
  }

  /** Checks if the images in the file are RGB. */
  public boolean isRGB(String id) throws FormatException, IOException {
    return false;
  }        
  
  /** Obtains the specified image from the given OIF file as a byte array. */
  public byte[] openBytes(String id, int no)
    throws FormatException, IOException
  {
    return tiffReader.openBytes((String) tiffs.get(no), 0);
  }

  /** Obtains the specified image from the given OIF file. */
  public BufferedImage openImage(String id, int no)
    throws FormatException, IOException
  {
    if (!id.equals(currentId)) initFile(id);

    if (no < 0 || no >= getImageCount(id)) {
      throw new FormatException("Invalid image number: " + no);
    }

    return tiffReader.openImage((String) tiffs.get(no), 0);
  }

  /** Closes any open files. */
  public void close() throws FormatException, IOException {
    if (reader != null) reader.close();
    reader = null;
    currentId = null;
  }

  /** Initializes the given OIF file. */
  protected void initFile(String id) throws FormatException, IOException {

    // check to make sure that we have the OIF file
    // if not, we need to look for it in the parent directory

    String oifFile = id;
    if (!id.toLowerCase().endsWith("oif")) {
      File current = new File(id);
      current = current.getAbsoluteFile();
      String parent = current.getParent();
      File tmp = new File(parent);
      parent = tmp.getParent();

      // strip off the filename

      id = current.getPath();

      oifFile = id.substring(id.lastIndexOf(File.separator));
      oifFile = parent + oifFile.substring(0, oifFile.indexOf("_")) + ".oif";

      tmp = new File(oifFile);
      if (!tmp.exists()) {
        oifFile = oifFile.substring(0, oifFile.lastIndexOf(".")) + ".OIF";
        tmp = new File(oifFile);
        if (!tmp.exists()) throw new FormatException("OIF file not found");
        currentId = oifFile;
      }
      else {
        currentId = oifFile;
      }
    }

    super.initFile(oifFile);
    reader = new BufferedReader(new FileReader(oifFile));
    tiffReader = new TiffReader();

    int slash = oifFile.lastIndexOf(File.separator);
    String path = slash < 0 ? "." : oifFile.substring(0, slash);

    // parse each key/value pair (one per line)

    Hashtable filenames = new Hashtable();
    String line = reader.readLine();
    while (line != null) {
      if (!line.startsWith("[") && (line.indexOf("=") > 0)) {
        String key = line.substring(0, line.indexOf("=") - 1).trim();
        String value = line.substring(line.indexOf("=") + 1).trim();
        key = DataTools.stripString(key);
        value = DataTools.stripString(value);
        if (key.startsWith("IniFileName") && key.indexOf("Thumb") == -1) {
          int pos = Integer.parseInt(key.substring(11));
          filenames.put(new Integer(pos), value);
        }
        metadata.put(key, value);
      }
      line = reader.readLine();
    }

    numImages = filenames.size();
    tiffs = new Vector(numImages);

    // open each INI file (.pty extension)

    String tiffPath;
    BufferedReader ptyReader;
    for (int i=0; i<numImages; i++) {
      String file = (String) filenames.get(new Integer(i));
      file = file.substring(1, file.length() - 1);
      file = file.replace('\\', File.separatorChar);
      file = file.replace('/', File.separatorChar);
      file = path + File.separator + file;
      tiffPath = file.substring(0, file.lastIndexOf(File.separator));

      ptyReader = new BufferedReader(new FileReader(file));
      line = ptyReader.readLine();
      while (line != null) {
        if (!line.startsWith("[") && (line.indexOf("=") > 0)) {
          String key = line.substring(0, line.indexOf("=") - 1).trim();
          String value = line.substring(line.indexOf("=") + 1).trim();
          key = DataTools.stripString(key);
          value = DataTools.stripString(value);
          if (key.equals("DataName")) {
            value = value.substring(1, value.length() - 1);
            tiffs.add(i, tiffPath + File.separator + value);
          }
          metadata.put("Image " + i + " : " + key, value);
        }
        line = ptyReader.readLine();
      }
      ptyReader.close();
    }

    if (ome != null) {
      OMETools.setPixels(ome,
        new Integer(Integer.parseInt((String) metadata.get("ImageWidth"))),
        new Integer(Integer.parseInt((String) metadata.get("ImageHeight"))),
        new Integer(numImages),
        new Integer(1),
        new Integer(1),
        "int" + (8*Integer.parseInt((String) metadata.get("ImageDepth"))),
        new Boolean(false),
        "XYZTC");
    }
  }


  // -- Main method --

  public static void main(String[] args) throws FormatException, IOException {
    new OIFReader().testRead(args);
  }

}
