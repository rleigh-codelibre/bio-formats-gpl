//
// ND2Handler.java
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

import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Hashtable;

import loci.common.DateTools;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.MetadataStore;

import ome.xml.model.primitives.NonNegativeInteger;
import ome.xml.model.primitives.PositiveInteger;

import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * DefaultHandler implementation for handling XML produced by Nikon Elements.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="http://loci.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/ND2Handler.java">Trac</a>,
 * <a href="http://loci.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/ND2Handler.java">SVN</a></dd></dl>
 */
public class ND2Handler extends DefaultHandler {

  // -- Constants --

  private static final String DATE_FORMAT = "dd/MM/yyyy  HH:mm:ss";

  // -- Fields --

  private String prefix = null;
  private String prevRuntype = null;
  private String prevElement = null;

  private Hashtable<String, Object> metadata = new Hashtable<String, Object>();
  private CoreMetadata[] core;

  private boolean isLossless;
  private ArrayList<Long> zs = new ArrayList<Long>();
  private ArrayList<Long> ts = new ArrayList<Long>();

  private int numSeries = 0;
  private double pixelSizeX, pixelSizeY, pixelSizeZ;

  private Double pinholeSize, voltage, mag, na;
  private String objectiveModel, immersion, correction;
  private Double refractiveIndex;
  private ArrayList<String> channelNames = new ArrayList<String>();
  private ArrayList<String> modality = new ArrayList<String>();
  private ArrayList<String> binning = new ArrayList<String>();
  private ArrayList<Double> speed = new ArrayList<Double>();
  private ArrayList<Double> gain = new ArrayList<Double>();
  private ArrayList<Double> temperature = new ArrayList<Double>();
  private ArrayList<Double> exposureTime = new ArrayList<Double>();
  private ArrayList<Integer> exWave = new ArrayList<Integer>();
  private ArrayList<Integer> emWave = new ArrayList<Integer>();
  private ArrayList<Integer> power = new ArrayList<Integer>();
  private ArrayList<Hashtable<String, String>> rois =
    new ArrayList<Hashtable<String, String>>();
  private ArrayList<Double> posX = new ArrayList<Double>();
  private ArrayList<Double> posY = new ArrayList<Double>();
  private ArrayList<Double> posZ = new ArrayList<Double>();

  private String cameraModel;
  private int fieldIndex = 0;
  private String date;

  private Hashtable<String, Integer> colors = new Hashtable<String, Integer>();
  private Hashtable<String, String> dyes = new Hashtable<String, String>();
  private Hashtable<String, Integer> realColors =
    new Hashtable<String, Integer>();

  // -- Constructor --

  public ND2Handler(CoreMetadata[] core) {
    super();
    this.core = core;
  }

  // -- ND2Handler API methods --

  public CoreMetadata[] getCoreMetadata() {
    return core;
  }

  public void populateROIs(MetadataStore store) {
    for (int r=0; r<rois.size(); r++) {
      Hashtable<String, String> roi = rois.get(r);
      String type = roi.get("ROIType");

      if (type.equals("Text")) {
        store.setROIID(MetadataTools.createLSID("ROI", r), r);
        store.setTextID(MetadataTools.createLSID("Shape", r, 0), r, 0);
        store.setTextFontSize(
          NonNegativeInteger.valueOf(roi.get("fHeight")), r, 0);
        store.setTextValue(roi.get("eval-text"), r, 0);
        store.setTextStrokeWidth(new Double(roi.get("line-width")), r, 0);

        String rectangle = roi.get("rectangle");
        String[] p = rectangle.split(",");
        double[] points = new double[p.length];
        for (int i=0; i<p.length; i++) {
          points[i] = Double.parseDouble(p[i]);
        }

        store.setRectangleID(MetadataTools.createLSID("Shape", r, 1), r, 1);
        store.setRectangleX(points[0], r, 1);
        store.setRectangleY(points[1], r, 1);
        store.setRectangleWidth(points[2] - points[0], r, 1);
        store.setRectangleHeight(points[3] - points[1], r, 1);
      }
      else if (type.equals("HorizontalLine") || type.equals("VerticalLine")) {
        store.setROIID(MetadataTools.createLSID("ROI", r), r);

        String segments = roi.get("segments");
        segments = segments.replaceAll("\\[\\]", "");
        String[] points = segments.split("\\)");

        StringBuffer sb = new StringBuffer();
        for (int i=0; i<points.length; i++) {
          points[i] = points[i].substring(points[i].indexOf(":") + 1);
          sb.append(points[i]);
          if (i < points.length - 1) sb.append(" ");
        }

        store.setPolylineID(MetadataTools.createLSID("Shape", r, 0), r, 0);
        store.setPolylinePoints(sb.toString(), r, 0);
      }
    }
  }

  public String getDate() {
    return date;
  }

  public Hashtable<String, Object> getMetadata() {
    return metadata;
  }

  public int getSeriesCount() {
    return numSeries;
  }

  public boolean isLossless() {
    return isLossless;
  }

  public ArrayList<Long> getZSections() {
    return zs;
  }

  public ArrayList<Long> getTimepoints() {
    return ts;
  }

  public double getPixelSizeX() {
    return pixelSizeX;
  }

  public double getPixelSizeY() {
    return pixelSizeY;
  }

  public double getPixelSizeZ() {
    return pixelSizeZ;
  }

  public Double getPinholeSize() {
    return pinholeSize;
  }

  public Double getVoltage() {
    return voltage;
  }

  public Double getMagnification() {
    return mag;
  }

  public Double getNumericalAperture() {
    return na;
  }

  public String getObjectiveModel() {
    return objectiveModel;
  }

  public String getImmersion() {
    return immersion;
  }

  public String getCorrection() {
    return correction;
  }

  public Double getRefractiveIndex() {
    return refractiveIndex;
  }

  public ArrayList<String> getChannelNames() {
    return channelNames;
  }

  public ArrayList<String> getModalities() {
    return modality;
  }

  public ArrayList<String> getBinnings() {
    return binning;
  }

  public ArrayList<Double> getSpeeds() {
    return speed;
  }

  public ArrayList<Double> getGains() {
    return gain;
  }

  public ArrayList<Double> getTemperatures() {
    return temperature;
  }

  public ArrayList<Double> getExposureTimes() {
    return exposureTime;
  }

  public ArrayList<Integer> getExcitationWavelengths() {
    return exWave;
  }

  public ArrayList<Integer> getEmissionWavelengths() {
    return emWave;
  }

  public ArrayList<Integer> getPowers() {
    return power;
  }

  public ArrayList<Hashtable<String, String>> getROIs() {
    return rois;
  }

  public ArrayList<Double> getXPositions() {
    return posX;
  }

  public ArrayList<Double> getYPositions() {
    return posY;
  }

  public ArrayList<Double> getZPositions() {
    return posZ;
  }

  public String getCameraModel() {
    return cameraModel;
  }

  public int getFieldIndex() {
    return fieldIndex;
  }

  public Hashtable<String, Integer> getChannelColors() {
    return realColors;
  }

  // -- DefaultHandler API methods --

  public void endElement(String uri, String localName, String qName,
    Attributes attributes)
  {
    if (qName.equals("CalibrationSeq") || qName.equals("MetadataSeq")) {
      prefix = null;
    }
    if (qName.equals(prevElement)) {
      prevElement = null;
    }
  }

  public void startElement(String uri, String localName, String qName,
    Attributes attributes)
  {
    if ("CLxListVariant".equals(attributes.getValue("runtype"))) {
      prevElement = qName;
    }

    String value = attributes.getValue("value");
    if (qName.equals("uiWidth")) {
      core[0].sizeX = Integer.parseInt(value);
    }
    else if (qName.equals("uiWidthBytes") || qName.equals("uiBpcInMemory")) {
      int div = qName.equals("uiWidthBytes") ? core[0].sizeX : 8;
      int bytes = Integer.parseInt(value) / div;

      try {
        core[0].pixelType =
          FormatTools.pixelTypeFromBytes(bytes, false, false);
      }
      catch (FormatException e) { }
      parseKeyAndValue(qName, value, prevRuntype);
    }
    else if ("dPosX".equals(prevElement) && qName.startsWith("item_")) {
      posX.add(new Double(sanitizeDouble(value)));
      metadata.put("X position for position #" + posX.size(), value);
    }
    else if ("dPosY".equals(prevElement) && qName.startsWith("item_")) {
      posY.add(new Double(sanitizeDouble(value)));
      metadata.put("Y position for position #" + posY.size(), value);
    }
    else if ("dPosZ".equals(prevElement) && qName.startsWith("item_")) {
      posZ.add(new Double(sanitizeDouble(value)));
      metadata.put("Z position for position #" + posZ.size(), value);
    }
    else if (qName.startsWith("item_")) {
      int v = Integer.parseInt(qName.substring(qName.indexOf("_") + 1));
      if (v == numSeries) {
        fieldIndex = core[0].dimensionOrder.length();
        numSeries++;
      }
      else if (v < numSeries && fieldIndex < core[0].dimensionOrder.length()) {
        fieldIndex = core[0].dimensionOrder.length();
      }
    }
    else if (qName.equals("uiCompCount")) {
      int v = Integer.parseInt(value);
      core[0].sizeC = (int) Math.max(core[0].sizeC, v);
    }
    else if (qName.equals("uiHeight")) {
      core[0].sizeY = Integer.parseInt(value);
    }
    else if (qName.startsWith("TextInfo")) {
      parseKeyAndValue(qName, attributes.getValue("Text"), prevRuntype);
      parseKeyAndValue(qName, value, prevRuntype);
    }
    else if (qName.equals("dCompressionParam")) {
      isLossless = Integer.parseInt(value) > 0;
      parseKeyAndValue(qName, value, prevRuntype);
    }
    else if (qName.equals("CalibrationSeq") || qName.equals("MetadataSeq")) {
      prefix = qName + " " + attributes.getValue("_SEQUENCE_INDEX");
    }
    else if (qName.equals("HorizontalLine") || qName.equals("VerticalLine") ||
      qName.equals("Text"))
    {
      Hashtable<String, String> roi = new Hashtable<String, String>();
      roi.put("ROIType", qName);
      for (int q=0; q<attributes.getLength(); q++) {
        roi.put(attributes.getQName(q), attributes.getValue(q));
      }
      rois.add(roi);
    }
    else if (qName.equals("dPinholeRadius")) {
      pinholeSize = new Double(sanitizeDouble(value));
      metadata.put("Pinhole size", value);
    }
    else if (qName.endsWith("ChannelColor")) {
      String name = qName.substring(0, qName.indexOf("Channel"));
      colors.put(name, new Integer(value));
    }
    else if (qName.endsWith("DyeName")) {
      int channelIndex = qName.indexOf("Channel");
      if (channelIndex < 0) channelIndex = 0;
      dyes.put(qName.substring(0, channelIndex), value);
    }
    else if (qName.equals("uiSequenceCount")) {
      int imageCount = Integer.parseInt(value);
      if (core.length > 0) imageCount /= core.length;
      if (core[0].sizeZ * core[0].sizeT != core[0].imageCount &&
        core[0].sizeZ * core[0].sizeC * core[0].sizeT != core[0].imageCount)
      {
        if (core[0].sizeZ > 1) {
          core[0].sizeZ = core[0].imageCount;
          core[0].sizeT = 1;
        }
        else if (core[0].sizeT > 1) {
          core[0].sizeT = core[0].imageCount;
          core[0].sizeZ = 1;
        }
      }
      metadata.put(qName, value);
    }
    else {
      StringBuffer sb = new StringBuffer();
      if (prefix != null) {
        sb.append(prefix);
        sb.append(" ");
      }
      sb.append(qName);
      parseKeyAndValue(sb.toString(), value, prevRuntype);
    }

    prevRuntype = attributes.getValue("runtype");
  }

  public void endDocument() {
    for (String name : colors.keySet()) {
      String chName = dyes.get(name);
      if (chName == null) chName = name;
      realColors.put(chName, colors.get(name));
    }
  }

  // -- Helper methods --

  private void parseKeyAndValue(String key, String value, String runtype) {
    if (key == null || value == null) return;
    metadata.put(key, value);
    if (key.endsWith("dCalibration")) {
      pixelSizeX = Double.parseDouble(sanitizeDouble(value));
      pixelSizeY = pixelSizeX;
    }
    else if (key.endsWith("dZStep")) {
      pixelSizeZ = Double.parseDouble(sanitizeDouble(value));
    }
    else if (key.endsWith("Gain")) {
      value = sanitizeDouble(value);
      if (!value.equals("")) {
        gain.add(new Double(value));
      }
    }
    else if (key.endsWith("dLampVoltage")) {
      voltage = new Double(sanitizeDouble(value));
    }
    else if (key.endsWith("dObjectiveMag") && mag == null) {
      mag = new Double(sanitizeDouble(value));
    }
    else if (key.endsWith("dObjectiveNA")) {
      na = new Double(sanitizeDouble(value));
    }
    else if (key.endsWith("dRefractIndex1")) {
      refractiveIndex = new Double(sanitizeDouble(value));
    }
    else if (key.equals("sObjective") || key.equals("wsObjectiveName") ||
      key.equals("sOptics"))
    {
      String[] tokens = value.split(" ");
      int magIndex = -1;
      for (int i=0; i<tokens.length; i++) {
        if (tokens[i].indexOf("x") != -1) {
          magIndex = i;
          break;
        }
      }
      StringBuffer s = new StringBuffer();
      for (int i=0; i<magIndex; i++) {
        s.append(tokens[i]);
      }
      correction = s.toString();
      if (magIndex >= 0) {
        String m = tokens[magIndex].substring(0, tokens[magIndex].indexOf("x"));
        m = sanitizeDouble(m);
        if (m.length() > 0) {
          mag = new Double(m);
        }
      }
      if (magIndex + 1 < tokens.length) immersion = tokens[magIndex + 1];
    }
    else if (key.endsWith("dTimeMSec")) {
      long v = (long) Double.parseDouble(sanitizeDouble(value));
      if (!ts.contains(new Long(v))) {
        ts.add(new Long(v));
        metadata.put("number of timepoints", ts.size());
      }
    }
    else if (key.endsWith("dZPos")) {
      long v = (long) Double.parseDouble(sanitizeDouble(value));
      if (!zs.contains(new Long(v))) {
        zs.add(new Long(v));
      }
    }
    else if (key.endsWith("uiCount")) {
      if (runtype != null) {
        if (runtype.endsWith("ZStackLoop")) {
          if (core[0].sizeZ == 0) {
            core[0].sizeZ = Integer.parseInt(value);
            if (core[0].dimensionOrder.indexOf("Z") == -1) {
              core[0].dimensionOrder = "Z" + core[0].dimensionOrder;
            }
          }
        }
        else if (runtype.endsWith("TimeLoop")) {
          if (core[0].sizeT == 0) {
            core[0].sizeT = Integer.parseInt(value);
            if (core[0].dimensionOrder.indexOf("T") == -1) {
              core[0].dimensionOrder = "T" + core[0].dimensionOrder;
            }
          }
        }
      }
    }
    else if (key.endsWith("uiBpcSignificant")) {
      core[0].bitsPerPixel = Integer.parseInt(value);
    }
    else if (key.equals("VirtualComponents")) {
      if (core[0].sizeC == 0) {
        core[0].sizeC = Integer.parseInt(value);
        if (core[0].dimensionOrder.indexOf("C") == -1) {
          core[0].dimensionOrder += "C" + core[0].dimensionOrder;
        }
      }
    }
    else if (key.startsWith("TextInfoItem") || key.endsWith("TextInfoItem")) {
      metadata.remove(key);
      value = value.replaceAll("&#x000d;&#x000a;", "\n");
      String[] tokens = value.split("\n");
      for (String t : tokens) {
        t = t.trim();
        if (t.startsWith("Dimensions:")) {
          t = t.substring(11);
          String[] dims = t.split(" x ");

          if (core[0].sizeZ == 0) core[0].sizeZ = 1;
          if (core[0].sizeT == 0) core[0].sizeT = 1;
          if (core[0].sizeC == 0) core[0].sizeC = 1;

          for (String dim : dims) {
            dim = dim.trim();
            int v = Integer.parseInt(dim.replaceAll("\\D", ""));
            v = (int) Math.max(v, 1);
            if (dim.startsWith("XY")) {
              numSeries = v;
              if (numSeries > 1) {
                int x = core[0].sizeX;
                int y = core[0].sizeY;
                int z = core[0].sizeZ;
                int tSize = core[0].sizeT;
                int c = core[0].sizeC;
                String order = core[0].dimensionOrder;
                core = new CoreMetadata[numSeries];
                for (int i=0; i<numSeries; i++) {
                  core[i] = new CoreMetadata();
                  core[i].sizeX = x;
                  core[i].sizeY = y;
                  core[i].sizeZ = z == 0 ? 1 : z;
                  core[i].sizeC = c == 0 ? 1 : c;
                  core[i].sizeT = tSize == 0 ? 1 : tSize;
                  core[i].dimensionOrder = order;
                }
              }
            }
            else if (dim.startsWith("T")) {
              if (core[0].sizeT <= 1 || v < core[0].sizeT) {
                core[0].sizeT = v;
              }
            }
            else if (dim.startsWith("Z")) {
              if (core[0].sizeZ <= 1) {
                core[0].sizeZ = v;
              }
            }
            else if (core[0].sizeC <= 1) {
              core[0].sizeC = v;
            }
          }

          core[0].imageCount = core[0].sizeZ * core[0].sizeC * core[0].sizeT;
        }
        else if (t.startsWith("Number of Picture Planes")) {
          core[0].sizeC = Integer.parseInt(t.replaceAll("\\D", ""));
        }
        else {
          String[] v = t.split(":");
          if (v.length == 2) {
            v[1] = v[1].trim();
            if (v[0].equals("Name")) {
              channelNames.add(v[1]);
            }
            else if (v[0].equals("Modality")) {
              modality.add(v[1]);
            }
            else if (v[0].equals("Camera Type")) {
              cameraModel = v[1];
            }
            else if (v[0].equals("Binning")) {
              binning.add(v[1]);
            }
            else if (v[0].equals("Readout Speed")) {
              int last = v[1].lastIndexOf(" ");
              if (last != -1) v[1] = v[1].substring(0, last);
              speed.add(new Double(sanitizeDouble(v[1])));
            }
            else if (v[0].equals("Temperature")) {
              String temp = v[1].replaceAll("[\\D&&[^-.]]", "");
              temperature.add(new Double(sanitizeDouble(temp)));
            }
            else if (v[0].equals("Exposure")) {
              String[] s = v[1].trim().split(" ");
              try {
                double time = Double.parseDouble(sanitizeDouble(s[0]));
                // TODO: check for other units
                if (s[1].equals("ms")) time /= 1000;
                exposureTime.add(new Double(time));
              }
              catch (NumberFormatException e) { }
            }
            else if (v[0].equals("{Pinhole Size}")) {
              pinholeSize = new Double(sanitizeDouble(v[1]));
              metadata.put("Pinhole size", v[1]);
            }
          }
          else if (v[0].startsWith("- Step")) {
            int space = v[0].indexOf(" ", v[0].indexOf("Step") + 1);
            int last = v[0].indexOf(" ", space + 1);
            if (last == -1) last = v[0].length();
            pixelSizeZ =
              Double.parseDouble(sanitizeDouble(v[0].substring(space, last)));
          }
          else if (v[0].equals("Line")) {
            String[] values = t.split(";");
            for (int q=0; q<values.length; q++) {
              int colon = values[q].indexOf(":");
              if (colon < 0) continue;
              String nextKey = values[q].substring(0, colon).trim();
              String nextValue = values[q].substring(colon + 1).trim();
              if (nextKey.equals("Emission wavelength")) {
                emWave.add(new Integer(nextValue));
              }
              else if (nextKey.equals("Excitation wavelength")) {
                exWave.add(new Integer(nextValue));
              }
              else if (nextKey.equals("Power")) {
                nextValue = sanitizeDouble(nextValue);
                power.add(new Integer((int) Double.parseDouble(nextValue)));
              }
            }
          }
          else if (v.length > 1) {
            v[0] = v[0].replace('{', ' ');
            v[0] = v[0].replace('}', ' ');
            metadata.put(v[0].trim(), v[1]);
          }
        }
      }
    }
    else if (key.equals("CameraUniqueName")) {
      cameraModel = value;
    }
    else if (key.equals("ExposureTime")) {
      exposureTime.add(new Double(value) / 1000d);
    }
    else if (key.equals("sDate")) {
      date = DateTools.formatDate(value, DATE_FORMAT);
    }
  }

  private String sanitizeDouble(String value) {
    value = value.replaceAll("[^0-9,\\.]", "");
    char separator = new DecimalFormatSymbols().getDecimalSeparator();
    if (value.indexOf(separator) == -1) {
      char usedSeparator = separator == '.' ? ',' : '.';
      value = value.replace(usedSeparator, separator);
      try {
        Double.parseDouble(value);
      }
      catch (Exception e) {
        value = value.replace(separator, usedSeparator);
      }
    }
    return value;
  }

}
