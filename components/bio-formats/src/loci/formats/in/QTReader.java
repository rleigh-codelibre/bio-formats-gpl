//
// QTReader.java
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

import loci.formats.*;

/**
 * QTReader is the file format reader for QuickTime movie files.
 * It does not read files directly, but chooses which QuickTime reader is
 * more appropriate.
 *
 * @see NativeQTReader
 * @see LegacyQTReader
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/components/bio-formats/src/loci/formats/in/QTReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/components/bio-formats/src/loci/formats/in/QTReader.java">SVN</a></dd></dl>
 */
public class QTReader extends DelegateReader {

  // -- Constructor --

  /** Constructs a new QuickTime reader. */
  public QTReader() {
    super("QuickTime", "mov");
    nativeReader = new NativeQTReader();
    legacyReader = new LegacyQTReader();
    nativeReaderInitialized = false;
    legacyReaderInitialized = false;
  }

}
