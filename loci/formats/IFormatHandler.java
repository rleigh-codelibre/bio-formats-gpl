//
// IFormatHandler.java
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

package loci.formats;

/** Interface for all biological file format readers and writers. */
public interface IFormatHandler extends StatusReporter {

  /** Checks if the given string is a valid filename for this file format. */
  boolean isThisType(String name);

  /**
   * Checks if the given string is a valid filename for this file format.
   * @param open If true, and the file extension is insufficient to determine
   *   the file type, the (existing) file is opened for further analysis.
   */
  boolean isThisType(String name, boolean open);

  /** Gets the name of this file format. */
  String getFormat();

  /** Gets the default file suffixes for this file format. */
  String[] getSuffixes();

}
