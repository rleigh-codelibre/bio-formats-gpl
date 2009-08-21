//
// FlexReader.java
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

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.Vector;

import loci.common.DataTools;
import loci.common.Location;
import loci.common.RandomAccessInputStream;
import loci.common.XMLTools;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.FilterMetadata;
import loci.formats.meta.MetadataStore;
import loci.formats.tiff.IFD;
import loci.formats.tiff.IFDList;
import loci.formats.tiff.TiffParser;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * FlexReader is a file format reader for Evotec Flex files.
 * To use it, the LuraWave decoder library, lwf_jsdk2.6.jar, must be available,
 * and a LuraWave license key must be specified in the lurawave.license system
 * property (e.g., <code>-Dlurawave.license=XXXX</code> on the command line).
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/FlexReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/FlexReader.java">SVN</a></dd></dl>
 */
public class FlexReader extends FormatReader {

  // -- Constants --

  /** Custom IFD entry for Flex XML. */
  public static final int FLEX = 65200;

  // -- Fields --

  /** Scale factor for each image. */
  protected double[][][] factors;

  /** Camera binning values. */
  private int binX, binY;

  private int plateCount;
  private int wellCount;
  private int fieldCount;

  private int wellRows, wellColumns;

  private Vector<String> channelNames;
  private Vector<Float> xPositions, yPositions;
  private Vector<Float> xSizes, ySizes;
  private Vector<String> cameraIDs, objectiveIDs, lightSourceIDs;
  private Hashtable<String, Vector<String>> lightSourceCombinationIDs;
  private Vector<String> cameraRefs, binnings, objectiveRefs;
  private Vector<String> lightSourceCombinationRefs;
  private Vector<String> filterSets;

  private Vector<String> measurementFiles;

  private String plateName, plateBarcode;

  /**
   * List of .flex files belonging to this dataset.
   * Indices into the array are the well row and well column.
   */
  private String[][] flexFiles;

  private IFDList[][] ifds;

  /** Specifies the row and column index into 'flexFiles' for a given well. */
  private int[][] wellNumber;

  // -- Constructor --

  /** Constructs a new Flex reader. */
  public FlexReader() { super("Evotec Flex", "flex"); }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#getUsedFiles(boolean) */
  public String[] getUsedFiles(boolean noPixels) {
    FormatTools.assertId(currentId, true, 1);
    Vector<String> files = new Vector<String>();
    if (!noPixels) {
      for (int i=0; i<flexFiles.length; i++) {
        for (int j=0; j<flexFiles[i].length; j++) {
          if (flexFiles[i][j] != null) files.add(flexFiles[i][j]);
        }
      }
    }
    for (String file : measurementFiles) {
      files.add(file);
    }
    return files.toArray(new String[files.size()]);
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.checkPlaneParameters(this, no, buf.length, x, y, w, h);

    int[] lengths = new int[] {fieldCount, wellCount, plateCount};
    int[] pos = FormatTools.rasterToPosition(lengths, getSeries());

    int imageNumber = getImageCount() * pos[0] + no;

    int wellRow = wellNumber[pos[1]][0];
    int wellCol = wellNumber[pos[1]][1];
    if (wellCount == 1) {
      wellRow = 0;
      wellCol = 0;
    }

    IFD ifd = ifds[wellRow][wellCol].get(imageNumber);
    RandomAccessInputStream s =
      new RandomAccessInputStream(flexFiles[wellRow][wellCol]);
    TiffParser tp = new TiffParser(s);

    int nBytes = ifd.getBitsPerSample()[0] / 8;

    // expand pixel values with multiplication by factor[no]
    byte[] bytes = tp.getSamples(ifd, buf, x, y, w, h);
    s.close();

    int bpp = FormatTools.getBytesPerPixel(getPixelType());
    int num = bytes.length / bpp;

    double factor = factors[wellRow][wellCol][imageNumber];

    if (factor != 1d || nBytes != bpp) {
      for (int i=num-1; i>=0; i--) {
        int q = nBytes == 1 ? bytes[i] & 0xff :
          DataTools.bytesToInt(bytes, i * bpp, bpp, isLittleEndian());
        q = (int) (q * factor);
        DataTools.unpackBytes(q, buf, i * bpp, bpp, isLittleEndian());
      }
    }
    else {
      System.arraycopy(bytes, 0, buf, 0, bytes.length);
    }

    return buf;
  }

  /* @see loci.formats.IFormatReader#close(boolean) */
  public void close(boolean fileOnly) throws IOException {
    super.close(fileOnly);
    if (!fileOnly) {
      factors = null;
      binX = binY = 0;
      plateCount = wellCount = fieldCount = 0;
      channelNames = null;
      measurementFiles = null;
      xSizes = ySizes = null;
      cameraIDs = objectiveIDs = lightSourceIDs = null;
      lightSourceCombinationIDs = null;
      lightSourceCombinationRefs = null;
      cameraRefs = objectiveRefs = binnings = null;
      wellRows = wellColumns = 0;
      xPositions = yPositions = null;
      filterSets = null;
      plateName = plateBarcode = null;
    }
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    debug("FlexReader.initFile(" + id + ")");
    super.initFile(id);

    measurementFiles = new Vector<String>();

    boolean doGrouping = true;

    Location currentFile = new Location(id).getAbsoluteFile();

    int nRows = 0, nCols = 0;
    Hashtable<String, String> v = new Hashtable<String, String>();

    try {
      String name = currentFile.getName();
      int[] well = getWell(name);
      if (well[0] > nRows) nRows = well[0];
      if (well[1] > nCols) nCols = well[1];
      v.put(well[0] + "," + well[1], currentFile.getAbsolutePath());
    }
    catch (NumberFormatException e) {
      traceDebug(e);
      doGrouping = false;
    }

    if (!isGroupFiles()) doGrouping = false;
    if (isGroupFiles()) {
      try {
        findMeasurementFiles(currentFile);
      }
      catch (NullPointerException e) {
        traceDebug(e);
      }
      catch (IOException e) {
        traceDebug(e);
      }
      if (measurementFiles.size() == 0) {
        warn("Measurement files not found.");
      }
    }

    MetadataStore store =
      new FilterMetadata(getMetadataStore(), isMetadataFiltered());

    if (doGrouping) {
      // group together .flex files that are in the same directory

      Location dir = currentFile.getParentFile();
      String[] files = dir.list(true);

      for (String file : files) {
        // file names should be nnnnnnnnn.flex, where 'n' is 0-9
        if (file.endsWith(".flex") && file.length() == 14 && !id.endsWith(file))
        {
          int[] well = getWell(file);
          if (well[0] > nRows) nRows = well[0];
          if (well[1] > nCols) nCols = well[1];
          String path = new Location(dir, file).getAbsolutePath();
          v.put(well[0] + "," + well[1], path);
        }
        else if (!id.endsWith(file)) {
          doGrouping = false;
          break;
        }
      }

      nRows++;
      nCols++;

      if (doGrouping) {
        flexFiles = new String[nRows][nCols];
        ifds = new IFDList[nRows][nCols];
        factors = new double[nRows][nCols][];
        wellCount = v.size();

        wellNumber = new int[wellCount][2];

        RandomAccessInputStream s = null;

        boolean firstFile = true;

        int currentWell = 0;
        for (int row=0; row<flexFiles.length; row++) {
          for (int col=0; col<flexFiles[row].length; col++) {
            flexFiles[row][col] = v.get(row + "," + col);
            if (flexFiles[row][col] == null) continue;
            wellNumber[currentWell][0] = row;
            wellNumber[currentWell][1] = col;
            s = new RandomAccessInputStream(flexFiles[row][col]);
            TiffParser tp = new TiffParser(s);
            ifds[row][col] = tp.getIFDs();
            s.close();

            parseFlexFile(currentWell, row, col, firstFile, store);
            if (firstFile) firstFile = false;
            currentWell++;
          }
        }
      }
    }

    if (!doGrouping) {
      wellCount = 1;
      flexFiles = new String[1][1];
      ifds = new IFDList[1][1];
      factors = new double[1][1][];
      flexFiles[0][0] = currentFile.getAbsolutePath();
      wellNumber = new int[][] {getWell(flexFiles[0][0])};

      RandomAccessInputStream s = new RandomAccessInputStream(flexFiles[0][0]);
      TiffParser tp = new TiffParser(s);
      ifds[0][0] = tp.getIFDs();
      s.close();

      parseFlexFile(0, 0, 0, true, store);
    }

    MetadataTools.populatePixels(store, this, true);
    String instrumentID = MetadataTools.createLSID("Instrument", 0);
    store.setInstrumentID(instrumentID, 0);

    if (plateName == null) plateName = currentFile.getParentFile().getName();
    if (plateBarcode != null) plateName = plateBarcode + " " + plateName;
    store.setPlateName(plateName, 0);
    store.setPlateRowNamingConvention("A", 0);
    store.setPlateColumnNamingConvention("1", 0);

    int[] lengths = new int[] {fieldCount, wellCount, plateCount};

    for (int row=0; row<wellRows; row++) {
      for (int col=0; col<wellColumns; col++) {
        store.setWellRow(new Integer(row), 0, row * wellColumns + col);
        store.setWellColumn(new Integer(col), 0, row * wellColumns + col);
      }
    }

    for (int i=0; i<getSeriesCount(); i++) {
      int[] pos = FormatTools.rasterToPosition(lengths, i);

      String imageID = MetadataTools.createLSID("Image", i);
      store.setImageID(imageID, i);
      store.setImageInstrumentRef(instrumentID, i);
      char wellRow = (char) ('A' + wellNumber[pos[1]][0]);
      store.setImageName("Well " + wellRow + "-" + (wellNumber[pos[1]][1] + 1) +
        "; Field #" + (pos[0] + 1), i);

      int seriesIndex = i * getImageCount();
      if (seriesIndex < objectiveRefs.size()) {
        store.setObjectiveSettingsObjective(objectiveRefs.get(seriesIndex), i);
      }

      if (seriesIndex < lightSourceCombinationRefs.size()) {
        String lightSourceCombo = lightSourceCombinationRefs.get(seriesIndex);
        Vector<String> lightSources =
          lightSourceCombinationIDs.get(lightSourceCombo);

        for (int c=0; c<getEffectiveSizeC(); c++) {
          int index = i * getImageCount() + c;
          if (index < cameraRefs.size()) {
            store.setDetectorSettingsDetector(cameraRefs.get(index), i, c);
          }
          if (index < binnings.size()) {
            store.setDetectorSettingsBinning(binnings.get(index), i, c);
          }
          if (lightSources != null && c < lightSources.size()) {
            store.setLightSourceSettingsLightSource(lightSources.get(c), i, c);
          }
          else if (c > 0 && lightSources != null && lightSources.size() == 1) {
            store.setLightSourceSettingsLightSource(lightSources.get(0), i, c);
          }
          if (index < filterSets.size()) {
            store.setLogicalChannelFilterSet(filterSets.get(index), i, c);
          }
        }
      }

      int sizeIndex = i * getImageCount();
      if (sizeIndex < xSizes.size()) {
        store.setDimensionsPhysicalSizeX(xSizes.get(sizeIndex), i, 0);
      }
      if (sizeIndex < ySizes.size()) {
        store.setDimensionsPhysicalSizeY(ySizes.get(sizeIndex), i, 0);
      }

      int well = wellNumber[pos[1]][0] * wellColumns + wellNumber[pos[1]][1];
      if (wellRows == 0 && wellColumns == 0) {
        well = pos[1];
        store.setWellRow(new Integer(wellNumber[pos[1]][0]), pos[2], pos[1]);
        store.setWellColumn(new Integer(wellNumber[pos[1]][1]), pos[2], pos[1]);
      }

      store.setWellSampleIndex(new Integer(i), pos[2], well, pos[0]);
      store.setWellSampleImageRef(imageID, pos[2], well, pos[0]);
      if (pos[0] < xPositions.size()) {
        store.setWellSamplePosX(xPositions.get(pos[0]), pos[2], well, pos[0]);
      }
      if (pos[0] < yPositions.size()) {
        store.setWellSamplePosY(yPositions.get(pos[0]), pos[2], well, pos[0]);
      }
    }
  }

  // -- Helper methods --

  /**
   * Returns a two-element array containing the well row and well column
   * corresponding to the given file.
   */
  private int[] getWell(String file) {
    String name = file.substring(file.lastIndexOf(File.separator) + 1);
    if (name.length() == 14) {
      // expect nnnnnnnnn.flex
      try {
        int row = Integer.parseInt(name.substring(0, 3)) - 1;
        int col = Integer.parseInt(name.substring(3, 6)) - 1;
        return new int[] {row, col};
      }
      catch (NumberFormatException e) { }
    }
    return new int[] {0, 0};
  }

  /**
   * Returns the IFDs of the first well that has data. May not be
   * <code>[0][0]</code> as the acquisition may have been column or row offset.
   * @return List of the first well's IFDs.
   */
  private IFDList firstWellIfds() {
    for (int i = 0; i < ifds.length; i++) {
      for (int j = 0; j < ifds[i].length; j++) {
        if (ifds[i][j] != null) return ifds[i][j];
      }
    }
    return null;
  }

  /**
   * Parses XML metadata from the Flex file corresponding to the given well.
   * If the 'firstFile' flag is set, then the core metadata is also
   * populated.
   */
  private void parseFlexFile(int currentWell, int wellRow, int wellCol,
    boolean firstFile, MetadataStore store)
    throws FormatException, IOException
  {
    if (flexFiles[wellRow][wellCol] == null) return;

    if (channelNames == null) channelNames = new Vector<String>();
    if (xPositions == null) xPositions = new Vector<Float>();
    if (yPositions == null) yPositions = new Vector<Float>();
    if (xSizes == null) xSizes = new Vector<Float>();
    if (ySizes == null) ySizes = new Vector<Float>();
    if (cameraIDs == null) cameraIDs = new Vector<String>();
    if (lightSourceIDs == null) lightSourceIDs = new Vector<String>();
    if (objectiveIDs == null) objectiveIDs = new Vector<String>();
    if (lightSourceCombinationIDs == null) {
      lightSourceCombinationIDs = new Hashtable<String, Vector<String>>();
    }
    if (lightSourceCombinationRefs == null) {
      lightSourceCombinationRefs = new Vector<String>();
    }
    if (cameraRefs == null) cameraRefs = new Vector<String>();
    if (objectiveRefs == null) objectiveRefs = new Vector<String>();
    if (binnings == null) binnings = new Vector<String>();
    if (filterSets == null) filterSets = new Vector<String>();

    // parse factors from XML
    IFD ifd = ifds[wellRow][wellCol].get(0);
    String xml = ifd.getIFDStringValue(FLEX, true);

    // HACK - workaround for Windows and Mac OS X bug where
    // SAX parser fails due to improperly handled mu (181) characters.
    byte[] c = xml.getBytes();
    for (int i=0; i<c.length; i++) {
      if (c[i] > '~' || (c[i] != '\t' && c[i] < ' ')) c[i] = ' ';
    }

    Vector n = new Vector();
    Vector f = new Vector();
    DefaultHandler handler =
      new FlexHandler(n, f, store, firstFile, currentWell);
    XMLTools.parseXML(c, handler);

    if (firstFile) populateCoreMetadata(wellRow, wellCol, n);

    int totalPlanes = getSeriesCount() * getImageCount();

    // verify factor count
    int nsize = n.size();
    int fsize = f.size();
    if (nsize != fsize || nsize != totalPlanes) {
      warnDebug("mismatch between image count, " +
        "names and factors (count=" + totalPlanes +
        ", names=" + nsize + ", factors=" + fsize + ")");
    }
    for (int i=0; i<nsize; i++) addGlobalMeta("Name " + i, n.get(i));
    for (int i=0; i<fsize; i++) addGlobalMeta("Factor " + i, f.get(i));

    // parse factor values
    factors[wellRow][wellCol] = new double[totalPlanes];
    int max = 0;
    for (int i=0; i<fsize; i++) {
      String factor = (String) f.get(i);
      double q = 1;
      try {
        q = Double.parseDouble(factor);
      }
      catch (NumberFormatException exc) {
        warnDebug("invalid factor #" + i + ": " + factor);
      }
      if (i < factors[wellRow][wellCol].length) {
        factors[wellRow][wellCol][i] = q;
        if (q > factors[wellRow][wellCol][max]) max = i;
      }
    }
    if (fsize < factors[wellRow][wellCol].length) {
      Arrays.fill(factors[wellRow][wellCol], fsize,
        factors[wellRow][wellCol].length, 1);
    }

    // determine pixel type
    if (factors[wellRow][wellCol][max] > 256) {
      core[0].pixelType = FormatTools.UINT32;
    }
    else if (factors[wellRow][wellCol][max] > 1) {
      core[0].pixelType = FormatTools.UINT16;
    }
    for (int i=1; i<core.length; i++) {
      core[i].pixelType = getPixelType();
    }
  }

  /** Populate core metadata using the given list of image names. */
  private void populateCoreMetadata(int wellRow, int wellCol,
    Vector<String> imageNames)
    throws FormatException
  {
    if (getSizeC() == 0 && getSizeT() == 0) {
      Vector<String> uniqueChannels = new Vector<String>();
      for (int i=0; i<imageNames.size(); i++) {
        String name = imageNames.get(i);
        String[] tokens = name.split("_");
        if (tokens.length > 1) {
          // fields are indexed from 1
          int fieldIndex = Integer.parseInt(tokens[0]);
          if (fieldIndex > fieldCount) fieldCount = fieldIndex;
        }
        else tokens = name.split(":");
        String channel = tokens[tokens.length - 1];
        if (!uniqueChannels.contains(channel)) uniqueChannels.add(channel);
      }
      if (fieldCount == 0) fieldCount = 1;
      core[0].sizeC = (int) Math.max(uniqueChannels.size(), 1);
      if (getSizeZ() == 0) core[0].sizeZ = 1;
      core[0].sizeT =
        imageNames.size() / (fieldCount * getSizeC() * getSizeZ());
    }

    if (getSizeC() == 0) {
      core[0].sizeC = (int) Math.max(channelNames.size(), 1);
    }

    if (getSizeZ() == 0) core[0].sizeZ = 1;
    if (getSizeT() == 0) core[0].sizeT = 1;
    if (plateCount == 0) plateCount = 1;
    if (wellCount == 0) wellCount = 1;
    if (fieldCount == 0) fieldCount = 1;

    // adjust dimensions if the number of IFDs doesn't match the number
    // of reported images

    IFDList ifdList = ifds[wellRow][wellCol];
    IFD ifd = ifdList.get(0);

    core[0].imageCount = getSizeZ() * getSizeC() * getSizeT();
    if (getImageCount() * fieldCount != ifdList.size()) {
      core[0].imageCount = ifdList.size() / fieldCount;
      core[0].sizeZ = 1;
      core[0].sizeC = 1;
      core[0].sizeT = ifdList.size() / fieldCount;
    }
    core[0].sizeX = (int) ifd.getImageWidth();
    core[0].sizeY = (int) ifd.getImageLength();
    core[0].dimensionOrder = "XYCZT";
    core[0].rgb = false;
    core[0].interleaved = false;
    core[0].indexed = false;
    core[0].littleEndian = ifd.isLittleEndian();
    core[0].pixelType = ifd.getPixelType();

    int seriesCount = plateCount * wellCount * fieldCount;
    if (seriesCount > 1) {
      CoreMetadata oldCore = core[0];
      core = new CoreMetadata[seriesCount];
      Arrays.fill(core, oldCore);
    }
  }

  /**
   * Search for measurement files (.mea, .res) that correspond to the
   * given Flex file.
   */
  private void findMeasurementFiles(Location flexFile) throws IOException {
    // we're assuming that the directory structure looks something like this:
    //
    //                        top level directory
    //                         /              \
    //           top level flex dir       top level measurement dir
    //              /     |    \                 /       |     \
    //        plate #0   ...   plate #n     plate #0    ...    plate #n
    //       /   |  \                        /   \
    //    .flex ... .flex                 .mea   .res
    //
    // or like this:
    //
    //                       top level directory
    //                       /  |  \      /  |  \
    //          Flex plate #0  ... #n    #0 ... Measurement plate #n
    //
    // or that the .mea and .res are in the same directory as the .flex files

    Location plateDir = flexFile.getParentFile();
    String[] files = plateDir.list(true);

    // check if the measurement files are in the same directory
    for (String file : files) {
      String lfile = file.toLowerCase();
      if (lfile.endsWith(".mea") || lfile.endsWith(".res")) {
        measurementFiles.add(new Location(plateDir, file).getAbsolutePath());
      }
    }
    if (measurementFiles.size() > 0) return;

    Location flexDir = null;
    try {
      flexDir = plateDir.getParentFile();
    }
    catch (NullPointerException e) { }
    if (flexDir == null) return;

    // check if the measurement directory and the Flex directory
    // have the same parent

    Location measurementDir = null;
    String[] flexDirList = flexDir.list(true);
    if (flexDirList.length > 1) {
      for (String file : flexDirList) {
        if (!file.equals(plateDir.getName()) &&
          plateDir.getName().startsWith(file))
        {
          measurementDir = new Location(flexDir, file);
          break;
        }
      }
    }

    // check if Flex directories and measurement directories have
    // a different parent

    if (measurementDir == null) {
      Location topDir = flexDir.getParentFile();

      String[] topDirList = topDir.list(true);
      for (String file : topDirList) {
        if (!flexDir.getAbsolutePath().endsWith(file)) {
          measurementDir = new Location(topDir, file);
          break;
        }
      }

      if (measurementDir == null) return;
    }
    else plateDir = measurementDir;

    if (!plateDir.getAbsolutePath().equals(measurementDir.getAbsolutePath())) {
      String[] measurementPlates = measurementDir.list(true);
      String plate = plateDir.getName();
      plateDir = null;
      if (measurementPlates != null) {
        for (String file : measurementPlates) {
          if (file.indexOf(plate) != -1 || plate.indexOf(file) != -1) {
            plateDir = new Location(measurementDir, file);
            break;
          }
        }
      }
    }

    if (plateDir == null) return;

    files = plateDir.list(true);
    for (String file : files) {
      measurementFiles.add(new Location(plateDir, file).getAbsolutePath());
    }
  }

  // -- Helper classes --

  /** SAX handler for parsing XML. */
  public class FlexHandler extends DefaultHandler {
    private Vector names, factors;
    private MetadataStore store;

    private int nextLaser = -1;
    private int nextCamera = 0;
    private int nextObjective = -1;
    private int nextImage = 0;
    private int nextPlate = 0;

    private String parentQName;
    private String lightSourceID;

    private String sliderName;
    private int nextFilter;
    private int nextDichroic;
    private int nextFilterSet;
    private int nextSliderRef;

    private boolean populateCore = true;
    private int well = 0;

    private Hashtable<String, String> filterMap;
    private Hashtable<String, String> dichroicMap;

    private StringBuffer charData = new StringBuffer();

    public FlexHandler(Vector names, Vector factors, MetadataStore store,
      boolean populateCore, int well)
    {
      this.names = names;
      this.factors = factors;
      this.store = store;
      this.populateCore = populateCore;
      this.well = well;
      filterMap = new Hashtable<String, String>();
      dichroicMap = new Hashtable<String, String>();
    }

    public void characters(char[] ch, int start, int length) {
      charData.append(new String(ch, start, length));
    }

    public void endElement(String uri, String localName, String qName) {
      String value = charData.toString();
      charData = new StringBuffer();

      if (qName.equals("Image")) {
        binnings.add(binX + "x" + binY);
      }
      else if (qName.equals("PlateName")) {
        if (plateName == null) plateName = value;
      }
      else if (qName.equals("Barcode")) {
        if (plateBarcode == null) plateBarcode = value;
        store.setPlateExternalIdentifier(value, nextPlate - 1);
      }
      else if (qName.equals("Wavelength")) {
        String lsid = MetadataTools.createLSID("LightSource", 0, nextLaser);
        store.setLightSourceID(lsid, 0, nextLaser);
        store.setLaserWavelength(new Integer(value), 0, nextLaser);
        store.setLaserType("Unknown", 0, nextLaser);
        store.setLaserLaserMedium("Unknown", 0, nextLaser);
      }
      else if (qName.equals("Magnification")) {
        store.setObjectiveCalibratedMagnification(new Float(value), 0,
          nextObjective);
      }
      else if (qName.equals("NumAperture")) {
        store.setObjectiveLensNA(new Float(value), 0, nextObjective);
      }
      else if (qName.equals("Immersion")) {
        if (value.equals("1.33")) value = "Water";
        else if (value.equals("1.00")) value = "Air";
        else warn("Unknown immersion medium: " + value);
        store.setObjectiveImmersion(value, 0, nextObjective);
      }
      else if (qName.equals("OffsetX") || qName.equals("OffsetY")) {
        Float offset = new Float(Float.parseFloat(value) * 1000000);
        if (qName.equals("OffsetX")) xPositions.add(offset);
        else yPositions.add(offset);
      }
      else if (qName.equals("XSize") && "Plate".equals(parentQName)) {
        wellRows = Integer.parseInt(value);
      }
      else if (qName.equals("YSize") && "Plate".equals(parentQName)) {
        wellColumns = Integer.parseInt(value);
      }
      else if ("Image".equals(parentQName)) {
        if (fieldCount == 0) fieldCount = 1;
        int nImages = firstWellIfds().size() / fieldCount;
        if (nImages == 0) nImages = 1; // probably a manually altered dataset
        int currentSeries = (nextImage - 1) / nImages;
        currentSeries += well * fieldCount;
        int currentImage = (nextImage - 1) % nImages;

        int seriesCount = 1;
        if (plateCount > 0) seriesCount *= plateCount;
        if (wellCount > 0) seriesCount *= wellCount;
        if (fieldCount > 0) seriesCount *= fieldCount;
        if (currentSeries >= seriesCount) return;

        if (qName.equals("DateTime")) {
          store.setImageCreationDate(value, currentSeries);
        }
        else if (qName.equals("CameraBinningX")) {
          binX = Integer.parseInt(value);
        }
        else if (qName.equals("CameraBinningY")) {
          binY = Integer.parseInt(value);
        }
        else if (qName.equals("ObjectiveRef")) {
          String objectiveID = MetadataTools.createLSID(
            "Objective", 0, objectiveIDs.indexOf(value));
          objectiveRefs.add(objectiveID);
        }
        else if (qName.equals("CameraRef")) {
          String detectorID =
            MetadataTools.createLSID("Detector", 0, cameraIDs.indexOf(value));
          cameraRefs.add(detectorID);
        }
        else if (qName.equals("ImageResolutionX")) {
          float v = Float.parseFloat(value) * 1000000;
          xSizes.add(new Float(v));
        }
        else if (qName.equals("ImageResolutionY")) {
          float v = Float.parseFloat(value) * 1000000;
          ySizes.add(new Float(v));
        }
        else if (qName.equals("PositionX")) {
          Float v = new Float(Float.parseFloat(value) * 1000000);
          store.setStagePositionPositionX(v, currentSeries, 0, currentImage);
        }
        else if (qName.equals("PositionY")) {
          Float v = new Float(Float.parseFloat(value) * 1000000);
          store.setStagePositionPositionY(v, currentSeries, 0, currentImage);
        }
        else if (qName.equals("PositionZ")) {
          Float v = new Float(Float.parseFloat(value) * 1000000);
          store.setStagePositionPositionZ(v, currentSeries, 0, currentImage);
        }
        else if (qName.equals("TimepointOffsetUsed")) {
          store.setPlaneTimingDeltaT(new Float(value), currentSeries, 0,
            currentImage);
        }
        else if (qName.equals("CameraExposureTime")) {
          store.setPlaneTimingExposureTime(new Float(value), currentSeries, 0,
            currentImage);
        }
        else if (qName.equals("LightSourceCombinationRef")) {
          lightSourceCombinationRefs.add(value);
        }
        else if (qName.equals("FilterCombinationRef")) {
          filterSets.add("FilterSet:" + value);
        }
      }
      else if (qName.equals("FilterCombination")) {
        nextFilterSet++;
        nextSliderRef = 0;
      }
    }

    public void startElement(String uri,
      String localName, String qName, Attributes attributes)
    {
      if (qName.equals("Array")) {
        int len = attributes.getLength();
        for (int i=0; i<len; i++) {
          String name = attributes.getQName(i);
          if (name.equals("Name")) {
            names.add(attributes.getValue(i));
          }
          else if (name.equals("Factor")) factors.add(attributes.getValue(i));
        }
      }
      else if (qName.equals("LightSource")) {
        parentQName = qName;
        String type = attributes.getValue("LightSourceType");

        lightSourceIDs.add(attributes.getValue("ID"));
        nextLaser++;
      }
      else if (qName.equals("LightSourceCombination")) {
        lightSourceID = attributes.getValue("ID");
        lightSourceCombinationIDs.put(lightSourceID, new Vector<String>());
      }
      else if (qName.equals("LightSourceRef")) {
        Vector<String> v = lightSourceCombinationIDs.get(lightSourceID);
        if (v != null) {
          int id = lightSourceIDs.indexOf(attributes.getValue("ID"));
          String lightSourceID = MetadataTools.createLSID("LightSource", 0, id);
          v.add(lightSourceID);
          lightSourceCombinationIDs.put(lightSourceID, v);
        }
      }
      else if (qName.equals("Camera")) {
        parentQName = qName;
        String detectorID = MetadataTools.createLSID("Detector", 0, nextCamera);
        store.setDetectorID(detectorID, 0, nextCamera);
        store.setDetectorType(attributes.getValue("CameraType"), 0, nextCamera);
        cameraIDs.add(attributes.getValue("ID"));
        nextCamera++;
      }
      else if (qName.equals("Objective")) {
        parentQName = qName;
        nextObjective++;

        String objectiveID =
          MetadataTools.createLSID("Objective", 0, nextObjective);
        store.setObjectiveID(objectiveID, 0, nextObjective);
        store.setObjectiveCorrection("Unknown", 0, nextObjective);
        objectiveIDs.add(attributes.getValue("ID"));
      }
      else if (qName.equals("Field")) {
        parentQName = qName;
        int fieldNo = Integer.parseInt(attributes.getValue("No"));
        if (fieldNo > fieldCount && fieldCount < firstWellIfds().size()) {
          fieldCount++;
        }
      }
      else if (qName.equals("Plane")) {
        parentQName = qName;
        int planeNo = Integer.parseInt(attributes.getValue("No"));
        if (planeNo > getSizeZ() && populateCore) core[0].sizeZ++;
      }
      else if (qName.equals("WellShape")) {
        parentQName = qName;
      }
      else if (qName.equals("Image")) {
        parentQName = qName;
        nextImage++;

        //Implemented for FLEX v1.7 and below
        String x = attributes.getValue("CameraBinningX");
        String y = attributes.getValue("CameraBinningY");
        if (x != null) binX = Integer.parseInt(x);
        if (y != null) binY = Integer.parseInt(y);
      }
      else if (qName.equals("Plate")) {
        parentQName = qName;
        if (qName.equals("Plate")) {
          nextPlate++;
          plateCount++;
        }
      }
      else if (qName.equals("WellCoordinate")) {
        if (wellNumber.length == 1) {
          wellNumber[0][0] = Integer.parseInt(attributes.getValue("Row")) - 1;
          wellNumber[0][1] = Integer.parseInt(attributes.getValue("Col")) - 1;
        }
      }
      else if (qName.equals("Slider")) {
        sliderName = attributes.getValue("Name");
      }
      else if (qName.equals("Filter")) {
        String id = attributes.getValue("ID");
        if (sliderName.endsWith("Dichro")) {
          String dichroicID =
            MetadataTools.createLSID("Dichroic", 0, nextDichroic);
          dichroicMap.put(id, dichroicID);
          store.setDichroicID(dichroicID, 0, nextDichroic);
          store.setDichroicModel(id, 0, nextDichroic);
          nextDichroic++;
        }
        else {
          String filterID = MetadataTools.createLSID("Filter", 0, nextFilter);
          filterMap.put(id, filterID);
          store.setFilterID(filterID, 0, nextFilter);
          store.setFilterModel(id, 0, nextFilter);
          store.setFilterFilterWheel(sliderName, 0, nextFilter);
          nextFilter++;
        }
      }
      else if (qName.equals("FilterCombination")) {
        String filterSetID =
          MetadataTools.createLSID("FilterSet", 0, nextFilterSet);
        store.setFilterSetID(filterSetID, 0, nextFilterSet);
      }
      else if (qName.equals("SliderRef")) {
        String filterName = attributes.getValue("Filter");
        String filterID = filterMap.get(filterName);
        String dichroicID = dichroicMap.get(filterName);
        String slider = attributes.getValue("ID");
        if (nextSliderRef == 0 && slider.startsWith("Camera")) {
          store.setFilterSetEmFilter(filterID, 0, nextFilterSet);
        }
        else if (nextSliderRef == 1 && slider.startsWith("Camera")) {
          store.setFilterSetExFilter(filterID, 0, nextFilterSet);
        }
        else if (slider.equals("Primary_Dichro")) {
          store.setFilterSetDichroic(dichroicID, 0, nextFilterSet);
        }
        String lname = filterName.toLowerCase();
        if (!lname.startsWith("empty") && !lname.startsWith("blocked")) {
          nextSliderRef++;
        }
      }
    }
  }

}
