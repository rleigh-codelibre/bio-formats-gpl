package loci.formats.in;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.stream.StreamResult;

import loci.common.Location;
import loci.common.services.DependencyException;
import loci.common.services.ServiceException;
import loci.common.services.ServiceFactory;
import loci.common.xml.XMLTools;
import loci.formats.CoreMetadata;
import loci.formats.FormatException;
import loci.formats.FormatReader;
import loci.formats.FormatTools;
import loci.formats.MetadataTools;
import loci.formats.meta.MetadataConverter;
import loci.formats.meta.MetadataStore;
import loci.formats.ome.OMEXMLMetadata;
import loci.formats.services.OMEXMLService;
import ome.xml.model.primitives.Color;
import ome.xml.model.primitives.NonNegativeInteger;
import ome.xml.model.primitives.PositiveFloat;
import ome.xml.model.primitives.PositiveInteger;
import ome.xml.model.primitives.Timestamp;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

public class CellVoyagerReader extends FormatReader
{

	private static final String SINGLE_TIFF_PATH_BUILDER = "Image/W%dF%03dT%04dZ%02dC%d.tif";

	private Location measurementFolder;

	private List< ChannelInfo > channelInfos;

	private List< WellInfo > wells;

	private List< Integer > timePoints;

	private Location measurementResultFile;

	private Location omeMeasurementFile;

	public CellVoyagerReader()
	{
		super( "CellVoyager", new String[] { "tif", "xml" } );
		this.suffixNecessary = false;
		this.suffixSufficient = false;
		this.hasCompanionFiles = true;
		this.datasetDescription = "Directory with 2 master files 'MeasurementResult.xml' and 'MeasurementResult.ome.xml', used to stich together several TIF files.";
		this.domains = new String[] { FormatTools.HISTOLOGY_DOMAIN, FormatTools.LM_DOMAIN, FormatTools.HCS_DOMAIN };
	}

	@Override
	public byte[] openBytes( final int no, final byte[] buf, final int x, final int y, final int w, final int h ) throws FormatException, IOException
	{
		FormatTools.checkPlaneParameters( this, no, buf.length, x, y, w, h );

		final CoreMetadata cm = core.get( getSeries() );

		final int nImagesPerTimepoint = cm.sizeC * cm.sizeZ;
		final int targetTindex = no / nImagesPerTimepoint;

		final int rem = no % nImagesPerTimepoint;
		final int targetZindex = rem / cm.sizeC;
		final int targetCindex = rem % cm.sizeC;

		final int[] indices = seriesToWellArea( getSeries() );
		final int wellIndex = indices[0];
		final int areaIndex = indices[1];
		final WellInfo well = wells.get( wellIndex );
		final AreaInfo area = well.areas.get( areaIndex );
		final MinimalTiffReader tiffReader = new MinimalTiffReader();

		for ( final FieldInfo field : area.fields )
		{

			String filename = String.format( SINGLE_TIFF_PATH_BUILDER, wellIndex + 1, field.index, targetTindex + 1, targetZindex + 1, targetCindex + 1 );
			filename = filename.replace( '\\', File.separatorChar );
			final Location image = new Location( measurementFolder, filename );
			if ( !image.exists() ) { throw new IOException( "Could not find required file: " + image ); }

			tiffReader.setId( image.getAbsolutePath() );

			// Tile size
			final int tw = channelInfos.get( 0 ).tileWidth;
			final int th = channelInfos.get( 0 ).tileHeight;

			// Field bounds in full final image, full width, full height
			// (referential named '0', as if x=0 and y=0).
			final int xbs0 = ( int ) field.xpixels;
			final int ybs0 = ( int ) field.ypixels;

			// Subimage bounds in full final image is simply x, y, x+w, y+h

			// Do they intersect?
			if ( x + w < xbs0 || xbs0 + tw < x || y + h < ybs0 || ybs0 + th < y )
			{
				continue;
			}

			// Common rectangle in reconstructed image referential.
			final int xs0 = Math.max( xbs0 - x, 0 );
			final int ys0 = Math.max( ybs0 - y, 0 );

			// Common rectangle in tile referential (named with '1').
			final int xs1 = Math.max( x - xbs0, 0 );
			final int ys1 = Math.max( y - ybs0, 0 );
			final int xe1 = Math.min( tw, x + w - xbs0 );
			final int ye1 = Math.min( th, y + h - ybs0 );
			final int w1 = xe1 - xs1;
			final int h1 = ye1 - ys1;

			if ( w1 <= 0 || h1 <= 0 )
			{
				continue;
			}

			// Get corresponding data.
			final byte[] bytes = tiffReader.openBytes( 0, xs1, ys1, w1, h1 );
			final int nbpp = cm.bitsPerPixel / 8;

			for ( int row1 = 0; row1 < h1; row1++ )
			{
				// Line index in tile coords
				final int ls1 = nbpp * ( row1 * w1 );
				final int length = nbpp * w1;

				// Line index in reconstructed image coords
				final int ls0 = nbpp * ( ( ys0 + row1 ) * w + xs0 );

				// Transfer
				System.arraycopy( bytes, ls1, buf, ls0, length );
			}
			tiffReader.close();
		}

		return buf;
	}

	@Override
	public int fileGroupOption( final String id ) throws FormatException, IOException
	{
		return FormatTools.MUST_GROUP;
	}

	@Override
	public int getRequiredDirectories( final String[] files ) throws FormatException, IOException
	{
		/*
		 * We only need the directory where there is the two xml files. The
		 * parent durectory seems to contain only hardware macros to load and
		 * eject the plate or slide.
		 */
		return 0;
	}

	@Override
	public boolean isSingleFile( final String id ) throws FormatException, IOException
	{
		return false;
	}

	@Override
	public boolean isThisType( final String name, final boolean open )
	{
		/*
		 * We want to be pointed to any file in the directory that contains
		 * 'MeasurementResult.xml'.
		 */
		final String localName = new Location( name ).getName();
		if ( localName.equals( "MeasurementResult.xml" ) ) { return true; }
		final Location parent = new Location( name ).getAbsoluteFile().getParentFile();
		final Location xml = new Location( parent, "MeasurementResult.xml" );
		if ( !xml.exists() ) { return false; }

		return super.isThisType( name, open );
	}

	@Override
	protected void initFile( final String id ) throws FormatException, IOException
	{
		super.initFile( id );

		measurementFolder = new Location( id ).getAbsoluteFile();
		if ( !measurementFolder.exists() ) { throw new IOException( "File " + id + " does not exist." ); }
		if ( !measurementFolder.isDirectory() )
		{
			measurementFolder = measurementFolder.getParentFile();
		}

		measurementResultFile = new Location( measurementFolder, "MeasurementResult.xml" );
		if ( !measurementResultFile.exists() ) { throw new IOException( "Could not find " + measurementResultFile + " in folder." ); }

		omeMeasurementFile = new Location( measurementFolder, "MeasurementResult.ome.xml" );
		if ( !omeMeasurementFile.exists() ) { throw new IOException( "Could not find " + omeMeasurementFile + " in folder." ); }

		/*
		 * Open MeasurementSettings file
		 */

		final DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
		DocumentBuilder dBuilder = null;
		try
		{
			dBuilder = dbFactory.newDocumentBuilder();
		}
		catch ( final ParserConfigurationException e )
		{
			LOGGER.debug( "", e );
		}
		Document msDocument = null;
		try
		{
			msDocument = dBuilder.parse( measurementResultFile.getAbsolutePath() );
		}
		catch ( final SAXException e )
		{
			LOGGER.debug( "", e );
		}

		msDocument.getDocumentElement().normalize();

		/*
		 * Open OME metadata file
		 */

		Document omeDocument = null;
		try
		{
			omeDocument = dBuilder.parse( omeMeasurementFile.getAbsolutePath() );
		}
		catch ( final SAXException e )
		{
			LOGGER.debug( "", e );
		}

		omeDocument.getDocumentElement().normalize();

		/*
		 * Extract metadata from MeasurementSetting.xml & OME xml file. This is
		 * where the core of parsing and fetching useful info happens
		 */

		readInfo( msDocument, omeDocument );
	}

	@Override
	public String[] getSeriesUsedFiles( final boolean noPixels )
	{
		FormatTools.assertId( currentId, true, 1 );

		if ( noPixels )
		{
			return new String[] { measurementResultFile.getAbsolutePath(), omeMeasurementFile.getAbsolutePath() };
		}
		else
		{
			final int[] indices = seriesToWellArea( getSeries() );
			final int wellIndex = indices[ 0 ];
			final int areaIndex = indices[ 1 ];
			final AreaInfo area = wells.get( wellIndex ).areas.get( areaIndex );
			final int nFields = area.fields.size();
			final String[] images = new String[ getImageCount() * nFields + 2 ];
			int index = 0;
			images[ index++ ] = measurementResultFile.getAbsolutePath();
			images[ index++ ] = omeMeasurementFile.getAbsolutePath();

			for ( final Integer timepoint : timePoints )
			{
				for ( int zslice = 1; zslice <= getSizeZ(); zslice++ )
				{
					for ( int channel = 1; channel <= getSizeC(); channel++ )
					{
						for ( final FieldInfo field : area.fields )
						{
							/*
							 * Here we compose file names on the fly assuming
							 * they follow the pattern below. Fragile I guess.
							 */
							images[ index++ ] = measurementFolder.getAbsolutePath() + String.format( SINGLE_TIFF_PATH_BUILDER, wellIndex + 1, field.index, timepoint, zslice, channel );
						}

					}
				}
			}
			return images;
		}

	}

	/*
	 * PRIVATE METHODS
	 */

	/**
	 * Returns the well index (in the field {@link #wells}) and the area index
	 * (in the field {@link WellInfo#areas} corresponding to the specified
	 * series.
	 *
	 * @param series
	 *            the desired series.
	 * @return the corresponding well index and area index, as a 2-element array
	 *         of <code>int[] { well, area }</code>.
	 */
	private int[] seriesToWellArea( final int series )
	{
		int nWell = -1;
		int seriesInc = -1;
		for ( final WellInfo well : wells )
		{
			nWell++;
			int nAreas = -1;
			for ( @SuppressWarnings( "unused" )
			final AreaInfo area : well.areas )
			{
				seriesInc++;
				nAreas++;
				if ( series == seriesInc ) { return new int[] { nWell, nAreas }; }
			}
		}
		throw new IllegalStateException( "Cannot find a well for series " + series );
	}

	private void readInfo( final Document msDocument, final Document omeDocument ) throws FormatException
	{
		/*
		 * Magnification.
		 *
		 * We need it early, because the file format reports only un-magnified
		 * sizes. So if we are to put proper metadata, we need to make the
		 * conversion to size measured at the sample level ourselves. I feel
		 * like this is fragile and most likely to change in a future version of
		 * the file format.
		 */

		final Element msRoot = msDocument.getDocumentElement();
		final double objectiveMagnification = Double.parseDouble( getChildText( msRoot, new String[] { "ObjectiveLens", "Magnification" } ) );
		// final double zoomLensMagnification = Double.parseDouble(
		// getChildText( msRoot, new String[] { "ZoomLens", "Magnification",
		// "Value" } ) );
		final double magnification = objectiveMagnification; // *
		// zoomLensMagnification;

		/*
		 * Read the ome.xml file. Since it is malformed, we need to parse all
		 * nodes, and add an "ID" attribute to those who do not have it.
		 */

		final NodeList nodeList = omeDocument.getElementsByTagName( "*" );
		for ( int i = 0; i < nodeList.getLength(); i++ )
		{
			final Node node = nodeList.item( i );
			if ( node.getNodeType() == Node.ELEMENT_NODE )
			{
				final NamedNodeMap atts = node.getAttributes();

				final Node namedItem = atts.getNamedItem( "ID" );
				if ( namedItem == null )
				{
					( ( Element ) node ).setAttribute( "ID", "none" );
				}
			}
		}

		/*
		 * For single-slice image, the PhysicalSizeZ can be 0, which will make
		 * the metadata read fail. Correct that.
		 */

		final Element pszEl = getChild( omeDocument.getDocumentElement(), new String[] { "Image", "Pixels" } );
		final double physicalSizeZ = Double.parseDouble( pszEl.getAttribute( "PhysicalSizeZ" ) );
		if ( physicalSizeZ <= 0 )
		{
			// default to 1 whatever
			pszEl.setAttribute( "PhysicalSizeZ", "" + 1 );
		}

		/*
		 * Now that the XML document is properly formed, we can build a metadata
		 * object from it.
		 */

		OMEXMLService service = null;
		String xml = null;
		try
		{
			xml = XMLTools.getXML( omeDocument );
		}
		catch ( final TransformerConfigurationException e2 )
		{
			LOGGER.debug( "", e2 );
		}
		catch ( final TransformerException e2 )
		{
			e2.printStackTrace();
		}
		try
		{
			service = new ServiceFactory().getInstance( OMEXMLService.class );
		}
		catch ( final DependencyException e1 )
		{
			LOGGER.debug( "", e1 );
		}
		OMEXMLMetadata omeMD = null;

		if ( service != null )
		{
			try
			{
				omeMD = service.createOMEXMLMetadata( xml );
			}
			catch ( final ServiceException e )
			{
				LOGGER.debug( "", e );
			}
		}

		// Correct pixel size for magnification
		omeMD.setPixelsPhysicalSizeX( new PositiveFloat( omeMD.getPixelsPhysicalSizeX( 0 ).getValue().doubleValue() / magnification ), 0 );
		omeMD.setPixelsPhysicalSizeY( new PositiveFloat( omeMD.getPixelsPhysicalSizeY( 0 ).getValue().doubleValue() / magnification ), 0 );

		// Time interval
		omeMD.setPixelsTimeIncrement( Double.valueOf( readFrameInterval( msDocument ) ), 0 );

		/*
		 * Channels
		 */

		final Element channelsEl = getChild( msRoot, "Channels" );
		final List< Element > channelEls = getChildren( channelsEl, "Channel" );
		channelInfos = new ArrayList< ChannelInfo >();
		int channelIndex = 0;
		for ( final Element channelEl : channelEls )
		{
			final boolean isEnabled = Boolean.parseBoolean( getChildText( channelEl, "IsEnabled" ) );
			if ( !isEnabled )
			{
				continue;
			}
			final ChannelInfo ci = readChannel( channelEl );
			channelInfos.add( ci );

			omeMD.setChannelColor( ci.color, 0, channelIndex++ );
		}

		// Read pixel sizes from OME metadata.
		final double pixelWidth = omeMD.getPixelsPhysicalSizeX( 0 ).getValue().doubleValue();
		final double pixelHeight = omeMD.getPixelsPhysicalSizeY( 0 ).getValue().doubleValue();

		/*
		 * Read tile size from channel info. This is weird, but it's like that.
		 * Since we build a multi-C image, we have to assume that all channels
		 * have the same dimension, even if the file format allows for changing
		 * the size, binning, etc. from channel to channel. Failure to load
		 * datasets that have this exoticity is to be sought here.
		 */

		final int tileWidth = channelInfos.get( 0 ).tileWidth;
		final int tileHeight = channelInfos.get( 0 ).tileHeight;

		/*
		 * Handle multiple wells.
		 *
		 * The same kind of remark apply: We assume that a channel setting can
		 * be applied to ALL wells. So this file reader will fail for dataset
		 * that have one well that has a different dimension that of others.
		 */

		/*
		 * First remark: there can be two modes to store Areas in the xml file:
		 * Either we define different areas for each well, and in that case, the
		 * areas are found as a child element of the well element. Either the
		 * definition of areas is common to all wells, and in that case they
		 * area defined in a separate element.
		 */

		final boolean sameAreaPerWell = Boolean.parseBoolean( getChildText( msRoot, "UsesSameAreaParWell" ) );
		List< AreaInfo > areas = null;
		if ( sameAreaPerWell )
		{
			final Element areasEl = getChild( msRoot, new String[] { "SameAreaUsingWell", "Areas" } );
			final List< Element > areaEls = getChildren( areasEl, "Area" );
			int areaIndex = 0;
			areas = new ArrayList< AreaInfo >( areaEls.size() );
			int fieldIndex = 1;
			for ( final Element areaEl : areaEls )
			{
				final AreaInfo area = readArea( areaEl, fieldIndex, pixelWidth, pixelHeight, tileWidth, tileHeight );
				area.index = areaIndex++;
				areas.add( area );

				// Continue incrementing field index across areas.
				fieldIndex = area.fields.get( area.fields.size() - 1 ).index + 1;
			}
		}

		final Element wellsEl = getChild( msRoot, "Wells" );
		final List< Element > wellEls = getChildren( wellsEl, "Well" );
		wells = new ArrayList< WellInfo >();
		for ( final Element wellEl : wellEls )
		{
			final boolean isWellEnabled = Boolean.parseBoolean( getChild( wellEl, "IsEnabled" ).getTextContent() );
			if ( isWellEnabled )
			{
				final WellInfo wi = readWellInfo( wellEl, pixelWidth, pixelHeight, tileWidth, tileHeight );
				if ( sameAreaPerWell )
				{
					wi.areas = areas;
				}

				wells.add( wi );
			}
		}

		/*
		 * Z range.
		 *
		 * In this file format, the Z range appears to be general: it applies to
		 * all fields of all wells.
		 */

		final int nZSlices = Integer.parseInt( getChildText( msRoot, new String[] { "ZStackConditions", "NumberOfSlices" } ) );

		/*
		 * Time points. They are general as well. Which just makes sense.
		 */

		timePoints = readTimePoints( msDocument );

		/*
		 * Populate CORE metadata for each area.
		 *
		 * This reader takes to convention that state that 1 area = 1 series. So
		 * if you have 10 wells with 2 areas in each well, and each area is made
		 * of 20 fields, you will get 20 series, and each series will be
		 * stitched from 20 fields.
		 */


		core.clear();
		for ( final WellInfo well : wells )
		{
			for ( final AreaInfo area : well.areas )
			{
				final CoreMetadata ms = new CoreMetadata();
				core.add( ms );

				ms.sizeX = area.width;
				ms.sizeY = area.height;
				ms.sizeZ = nZSlices;
				ms.sizeC = channelInfos.size();
				ms.sizeT = timePoints.size();
				ms.dimensionOrder = "XYCZT";
				ms.rgb = false;
				ms.imageCount = nZSlices * channelInfos.size() * timePoints.size();

				// Bit depth.
				switch ( omeMD.getPixelsType( 0 ) )
				{
				case UINT8:
					ms.pixelType = FormatTools.UINT8;
					ms.bitsPerPixel = 8;
					break;
				case UINT16:
					ms.pixelType = FormatTools.UINT16;
					ms.bitsPerPixel = 16;
					break;
				case UINT32:
					ms.pixelType = FormatTools.UINT32;
					ms.bitsPerPixel = 32;
					break;
				default:
					throw new FormatException( "Cannot read image with pixel type = " + omeMD.getPixelsType( 0 ) );
				}

				// Determined manually on sample data. Check here is the image
				// you get is weird.
				ms.littleEndian = true;
			}
		}

		/*
		 * Populate the MetadataStore.
		 */

		final MetadataStore store = makeFilterMetadata();
		MetadataTools.populatePixels( store, this, true );
		MetadataConverter.convertMetadata( omeMD, store );

		/*
		 * Pinhole disk
		 */

		final double pinholeSize = Double.parseDouble( getChildText( msRoot, new String[] { "PinholeDisk", "PinholeSize_um" } ) );


		/*
		 * MicroPlate specific stuff
		 */

		final Element containerEl = getChild( msRoot, new String[] { "Attachment", "HolderInfoList", "HolderInfo", "MountedSampleContainer" } );
		final String type = containerEl.getAttribute( "xsi:type" );
		if ( type.equals( "WellPlate" ) )
		{
			// I don't know an other case. I can just hope that if there is
			// another name for microplate I will find out quickly.

			store.setPlateID( MetadataTools.createLSID( "Plate", 0 ), 0 );

			final int nrows = Integer.parseInt( getChildText( containerEl, "RowCount" ) );
			final int ncols = Integer.parseInt( getChildText( containerEl, "ColumnCount" ) );
			store.setPlateRows( new PositiveInteger( nrows ), 0 );
			store.setPlateColumns( new PositiveInteger( ncols ), 0 );

			final String plateAcqID = MetadataTools.createLSID( "PlateAcquisition", 0, 0 );
			store.setPlateAcquisitionID( plateAcqID, 0, 0 );

			final Element dimInfoEl = getChild( msRoot, "DimensionsInfo" );
			final int maxNFields = Integer.parseInt( getChild( dimInfoEl, "F" ).getAttribute( "Max" ) );
			final PositiveInteger fieldCount = FormatTools.getMaxFieldCount( maxNFields );
			if ( fieldCount != null )
			{
				store.setPlateAcquisitionMaximumFieldCount( fieldCount, 0, 0 );
			}

			// Plate acquisition time
			final String beginTime = getChildText( msRoot, "BeginTime" );
			final String endTime = getChildText( msRoot, "EndTime" );
			store.setPlateAcquisitionStartTime( new Timestamp( beginTime ), 0, 0 );
			store.setPlateAcquisitionEndTime( new Timestamp( endTime ), 0, 0 );
		}

		// Wells position on the plate
		int seriesIndex = -1;
		int wellIndex = -1;
		for ( final WellInfo well : wells )
		{
			wellIndex++;
			final int wellNumber = well.number;
			store.setWellRow( new NonNegativeInteger( well.row ), 0, wellIndex );
			store.setWellColumn( new NonNegativeInteger( well.col ), 0, wellIndex );
			store.setWellID( "" + well.UID, 0, wellIndex );
			int areaIndex = -1;
			for ( final AreaInfo area : well.areas )
			{
				seriesIndex++;
				areaIndex++;
				final String imageName = "Well " + wellNumber + " (r=" + well.row + ", c=" + well.col + ") - Area " + areaIndex;
				store.setImageName(imageName, seriesIndex );

				store.setWellSampleIndex( new NonNegativeInteger( area.index ), 0, wellIndex, areaIndex );
				store.setWellSampleID( "" + area.UID, 0, wellIndex, areaIndex );
				store.setWellSamplePositionX( Double.valueOf( well.centerX ), 0, wellIndex, areaIndex );
				store.setWellSamplePositionY( Double.valueOf( well.centerY ), 0, wellIndex, areaIndex );

				channelIndex = 0;
				for ( int i = 0; i < channelInfos.size(); i++ )
				{
					store.setChannelPinholeSize( pinholeSize, seriesIndex, channelIndex++ );
					store.setChannelName( channelInfos.get( i ).name, seriesIndex, i );
				}
			}

		}




	}

	private ChannelInfo readChannel( final Element channelEl )
	{
		final ChannelInfo ci = new ChannelInfo();

		ci.isEnabled = Boolean.parseBoolean( getChildText( channelEl, "IsEnabled" ) );

		ci.channelNumber = Integer.parseInt( getChildText( channelEl, "Number" ) );

		final Element acquisitionSettings = getChild( channelEl, "AcquisitionSetting" );

		final Element cameraEl = getChild( acquisitionSettings, "Camera" );
		ci.tileWidth = Integer.parseInt( getChildText( cameraEl, "EffectiveHorizontalPixels_pixel" ) );
		ci.tileHeight = Integer.parseInt( getChildText( cameraEl, "EffectiveVerticalPixels_pixel" ) );

		ci.unmagnifiedPixelWidth = Double.parseDouble( getChildText( cameraEl, "HorizonalCellSize_um" ) );
		ci.unmagnifiedPixelHeight = Double.parseDouble( getChildText( cameraEl, "VerticalCellSize_um" ) );

		final Element colorElement = getChild( channelEl, new String[] { "ContrastEnhanceParam", "Color" } );
		final int r = Integer.parseInt( getChildText( colorElement, "R" ) );
		final int g = Integer.parseInt( getChildText( colorElement, "G" ) );
		final int b = Integer.parseInt( getChildText( colorElement, "B" ) );
		final int a = Integer.parseInt( getChildText( colorElement, "A" ) );
		final Color channelColor = new Color( r, g, b, a );
		ci.color = channelColor;

		// Build a channel name from excitation, emission and fluorophore name
		final String excitationType = getChild( channelEl, "Excitation" ).getAttribute( "xsi:type" );
		final String excitationName = getChildText( channelEl, new String[] { "Excitation", "Name", "Value" } );
		final String emissionName = getChildText( channelEl, new String[] { "Emission", "Name", "Value" } );
		String fluorophoreName = getChildText( channelEl, new String[] { "Emission", "FluorescentProbe", "Value" } );
		if ( null == fluorophoreName )
		{
			fluorophoreName = "ø";
		}
		final String channelName = "Ex: " + excitationType + "(" + excitationName + ") / Em: " + emissionName + " / Fl: " + fluorophoreName;
		ci.name = channelName;

		return ci;

	}

	private WellInfo readWellInfo( final Element wellEl, final double pixelWidth, final double pixelHeight, final int tileWidth, final int tileHeight )
	{
		final WellInfo info = new WellInfo();
		info.UID = Integer.parseInt( getChildText( wellEl, "UniqueID" ) );
		info.number = Integer.parseInt( getChildText( wellEl, "Number" ) );
		info.row = Integer.parseInt( getChildText( wellEl, "Row" ) );
		info.col = Integer.parseInt( getChildText( wellEl, "Column" ) );
		info.centerX = Double.parseDouble( getChildText( wellEl, new String[] { "CenterCoord_mm", "X" } ) );
		info.centerY = Double.parseDouble( getChildText( wellEl, new String[] { "CenterCoord_mm", "Y" } ) );

		final Element areasEl = getChild( wellEl, "Areas" );
		final List< Element > areaEls = getChildren( areasEl, "Area" );
		int areaIndex = 0;
		int fieldIndex = 1;
		for ( final Element areaEl : areaEls )
		{
			final AreaInfo area = readArea( areaEl, fieldIndex, pixelWidth, pixelHeight, tileWidth, tileHeight );
			area.index = areaIndex++;
			info.areas.add( area );

			// Continue incrementing field index across areas.
			fieldIndex = area.fields.get( area.fields.size() - 1 ).index + 1;
		}

		return info;
	}

	private AreaInfo readArea( final Element areaEl, int startingFieldIndex, final double pixelWidth, final double pixelHeight, final int tileWidth, final int tileHeight )
	{
		final AreaInfo info = new AreaInfo();
		info.UID = Integer.parseInt( getChildText( areaEl, "UniqueID" ) );

		// Read field position in um
		double xmin = Double.POSITIVE_INFINITY;
		double ymin = Double.POSITIVE_INFINITY;
		double xmax = Double.NEGATIVE_INFINITY;
		double ymax = Double.NEGATIVE_INFINITY;

		final Element fieldsEl = getChild( areaEl, "Fields" );
		final List< Element > fieldEls = getChildren( fieldsEl, "Field" );

		// Read basic info and get min & max.
		for ( final Element fieldEl : fieldEls )
		{
			final FieldInfo finfo = readField( fieldEl );
			info.fields.add( finfo );

			final double xum = finfo.x;
			if ( xum < xmin )
			{
				xmin = xum;
			}
			if ( xum > xmax )
			{
				xmax = xum;
			}

			final double yum = -finfo.y;
			if ( yum < ymin )
			{
				ymin = yum;
			}
			if ( yum > ymax )
			{
				ymax = yum;
			}
		}
		for ( final FieldInfo finfo : info.fields )
		{
			final long xpixels = Math.round( ( finfo.x - xmin ) / pixelWidth );
			/*
			 * Careful! For the fields to be padded correctly, we need to invert
			 * their Y position, so that it matches the pixel orientation.
			 */
			final long ypixels = Math.round( ( -ymin - finfo.y ) / pixelHeight );
			finfo.xpixels = xpixels;
			finfo.ypixels = ypixels;

			/*
			 * Field index.
			 *
			 * Now there is a complexity regarding the way fields (that is:
			 * tiles in common meaning) are indexed in the 'ImageIndex.xml'
			 * file. Even if for a well you have two areas made of 5 tiles each,
			 * there is no indexing of the areas. The field index simply keeps
			 * increasing when you go from one area to the next one, and this
			 * index follows the appearance order of the 'Field' xml element in
			 * the 'MeasurementResult.xml' file.
			 */

			finfo.index = startingFieldIndex++;
		}

		final int width = 1 + ( int ) ( ( xmax - xmin ) / pixelWidth );
		final int height = 1 + ( int ) ( ( ymax - ymin ) / pixelHeight );
		info.width = width + tileWidth;
		info.height = height + tileHeight;



		return info;
	}

	private FieldInfo readField( final Element fieldEl )
	{
		final FieldInfo info = new FieldInfo();
		info.x = Double.parseDouble( getChildText( fieldEl, "StageX_um" ) );
		info.y = Double.parseDouble( getChildText( fieldEl, "StageY_um" ) );
		// I discarded the other info (BottomOffset & co) for I don't what to do
		// with them.
		return info;
	}

	private List< Integer > readTimePoints( final Document document )
	{
		final Element root = document.getDocumentElement();
		final int nTimePoints = Integer.parseInt( getChildText( root, new String[] { "TimelapsCondition", "Iteration" } ) );
		//
		final List< Integer > timepoints = new ArrayList< Integer >( nTimePoints );
		for ( int i = 0; i < nTimePoints; i++ )
		{
			timepoints.add( Integer.valueOf( i ) );
		}
		return timepoints;
	}

	private double readFrameInterval( final Document document )
	{
		final Element root = document.getDocumentElement();
		final double dt = Double.parseDouble( getChildText( root, new String[] { "TimelapsCondition", "Interval" } ) );
		return dt;
	}

	/*
	 * INNER CLASSES
	 */

	private static final class FieldInfo
	{

		public int index;

		public long ypixels;

		public long xpixels;

		public double y;

		public double x;

		@Override
		public String toString()
		{
			return "\t\tField index = " + index + "\n\t\t\tX = " + x + " µm\n\t\t\tY = " + y + " µm\n" + "\t\t\txi = " + xpixels + " pixels\n" + "\t\t\tyi = " + ypixels + " pixels\n";
		}

	}

	private static final class AreaInfo
	{

		public int index;

		public int height;

		public int width;

		public List< FieldInfo > fields = new ArrayList< FieldInfo >();

		public int UID;

		@Override
		public String toString()
		{
			final StringBuilder str = new StringBuilder();
			str.append( "\tArea ID = " + UID + '\n' );
			str.append( "\t\ttotal width = " + width + " pixels\n" );
			str.append( "\t\ttotal height = " + height + " pixels\n" );
			for ( final FieldInfo fieldInfo : fields )
			{
				str.append( fieldInfo.toString() );
			}
			return str.toString();
		}

	}

	private static final class WellInfo
	{

		public List< AreaInfo > areas = new ArrayList< AreaInfo >();

		public double centerY;

		public double centerX;

		public int col;

		public int row;

		public int number;

		public int UID;

		@Override
		public String toString()
		{
			final StringBuilder str = new StringBuilder();
			str.append( "Well ID = " + UID + '\n' );
			str.append( "\tnumber = " + number + '\n' );
			str.append( "\trow = " + row + '\n' );
			str.append( "\tcol = " + col + '\n' );
			str.append( "\tcenter X = " + centerX + " mm\n" );
			str.append( "\tcenter Y = " + centerY + " mm\n" );
			for ( final AreaInfo areaInfo : areas )
			{
				str.append( areaInfo.toString() );
			}
			return str.toString();
		}

	}

	private static final class ChannelInfo
	{

		public String name;

		public Color color;

		public int height;

		public int width;

		public boolean isEnabled;

		public double unmagnifiedPixelHeight;

		public double unmagnifiedPixelWidth;

		public int tileHeight;

		public int tileWidth;

		public int channelNumber;

		@Override
		public String toString()
		{
			final StringBuffer str = new StringBuffer();
			str.append( "Channel " + channelNumber + ": \n" );
			str.append( " - name: " + name + "\n" );
			str.append( " - isEnabled: " + isEnabled + "\n" );
			str.append( " - width: " + width + "\n" );
			str.append( " - height: " + height + "\n" );
			str.append( " - tile width: " + tileWidth + "\n" );
			str.append( " - tile height: " + tileHeight + "\n" );
			str.append( " - unmagnifiedPixelWidth: " + unmagnifiedPixelWidth + "\n" );
			str.append( " - unmagnifiedPixelHeight: " + unmagnifiedPixelHeight + "\n" );
			return str.toString();
		}

	}

	private static final Element getChild( final Element parent, final String childName )
	{
		final NodeList childNodes = parent.getChildNodes();
		for ( int i = 0; i < childNodes.getLength(); i++ )
		{
			final Node item = childNodes.item( i );
			if ( item.getNodeName().equals( childName ) ) { return ( Element ) item; }
		}
		return null;
	}

	private static final Element getChild( final Element parent, final String[] path )
	{
		if ( path.length == 1 ) { return getChild( parent, path[ 0 ] ); }

		final NodeList childNodes = parent.getChildNodes();
		for ( int i = 0; i < childNodes.getLength(); i++ )
		{
			final Node item = childNodes.item( i );
			if ( item.getNodeName().equals( path[ 0 ] ) ) { return getChild( ( Element ) item, Arrays.copyOfRange( path, 1, path.length ) ); }
		}
		return null;
	}

	private static final List< Element > getChildren( final Element parent, final String name )
	{
		final NodeList nodeList = parent.getElementsByTagName( name );
		final int nEls = nodeList.getLength();
		final List< Element > children = new ArrayList< Element >( nEls );
		for ( int i = 0; i < nEls; i++ )
		{
			children.add( ( Element ) nodeList.item( i ) );
		}
		return children;
	}

	private static final String getChildText( final Element parent, final String[] path )
	{
		if ( path.length == 1 ) { return getChildText( parent, path[ 0 ] ); }

		final NodeList childNodes = parent.getChildNodes();
		for ( int i = 0; i < childNodes.getLength(); i++ )
		{
			final Node item = childNodes.item( i );
			if ( item.getNodeName().equals( path[ 0 ] ) ) { return getChildText( ( Element ) item, Arrays.copyOfRange( path, 1, path.length ) ); }
		}
		return null;
	}

	private static final String getChildText( final Element parent, final String childName )
	{
		final NodeList childNodes = parent.getChildNodes();
		for ( int i = 0; i < childNodes.getLength(); i++ )
		{
			final Node item = childNodes.item( i );
			if ( item.getNodeName().equals( childName ) ) { return item.getTextContent(); }
		}
		return null;
	}

	@SuppressWarnings( "unused" )
	private static final String prettyPrintXML( final String xml )
	{
		try
		{
			final Transformer serializer = SAXTransformerFactory.newInstance().newTransformer();

			serializer.setOutputProperty( OutputKeys.INDENT, "yes" );

			serializer.setOutputProperty( "{http://xml.apache.org/xslt}indent-amount", "2" );
			final Source xmlSource = new SAXSource( new InputSource( new ByteArrayInputStream( xml.getBytes( "utf-8" ) ) ) );
			final StreamResult res = new StreamResult( new ByteArrayOutputStream() );

			serializer.transform( xmlSource, res );

			return new String( ( ( ByteArrayOutputStream ) res.getOutputStream() ).toByteArray() );
		}
		catch ( final Exception e )
		{
			e.printStackTrace();
			return null;
		}
	}

	@SuppressWarnings( "unused" )
	private static final String prettyPrintXML( final Document doc )
	{
		Transformer transformer = null;
		try
		{
			transformer = TransformerFactory.newInstance().newTransformer();
		}
		catch ( final TransformerConfigurationException e )
		{
			e.printStackTrace();
		}
		catch ( final TransformerFactoryConfigurationError e )
		{
			e.printStackTrace();
		}
		transformer.setOutputProperty( OutputKeys.INDENT, "yes" );
		final StreamResult result = new StreamResult( new StringWriter() );
		final DOMSource source = new DOMSource( doc );
		try
		{
			transformer.transform( source, result );
		}
		catch ( final TransformerException e )
		{
			e.printStackTrace();
		}
		final String xmlString = result.getWriter().toString();
		return xmlString;
	}

	public static void main( final String[] args ) throws IOException, FormatException, ServiceException
	{
		// final String id =
		// "/Users/tinevez/Projects/EArena/Data/TestDataset/20131025T092701/MeasurementSetting.xml";
		// final String id =
		// "/Users/tinevez/Projects/EArena/Data/30um sections at 40x - last round/1_3_1_2_2/20130731T133622/MeasurementResult.xml";
		final String id = "/Users/tinevez/Projects/EArena/Data/TestDataset/20131030T142837";

		final CellVoyagerReader importer = new CellVoyagerReader();
		importer.setId( id );

		final List< CoreMetadata > cms = importer.getCoreMetadataList();
		for ( final CoreMetadata coreMetadata : cms )
		{
			System.out.println( coreMetadata );
		}

		final Hashtable< String, Object > meta = importer.getGlobalMetadata();
		final String[] keys = MetadataTools.keys( meta );
		for ( final String key : keys )
		{
			System.out.println( key + " = " + meta.get( key ) );
		}

		importer.openBytes( 0 );

		importer.setSeries( 1 );
		final String[] usedFiles = importer.getSeriesUsedFiles();
		for ( final String file : usedFiles )
		{
			System.out.println( "  " + file );
		}

		importer.close();

	}
}
