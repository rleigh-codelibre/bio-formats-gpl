function metadata = createMinimalMetadata(I, varargin)
% CREATEMINIMALMETADATA Save a 5D matrix into an OME-TIFF using Bio-Formats library
%
%    createMinimalMetadata(I, outputPath, dimensionOrder) specifies the dimension order of
%    the input matrix. Default valuse is XYZCT.
%
%    createMinimalMetadata(I, outputPath, dimensionOrder) specifies the dimension order of
%    the input matrix. Default valuse is XYZCT.
%
%    Examples:
%
%        metadata = createMinimalOMEXMLMetadata(zeros(100, 100));
%        bfsave(zeros(100, 100, 2, 3, 4), outputPath)
%        bfsave(zeros(100, 100, 20), outputPath, 'dimensionOrder', 'XYTZC')
%        bfsave(zeros(100, 100), outputPath, 'Compression', 'LZW')
%        bfsave(zeros(100, 100), outputPath, 'BigTiff', true)
%
% See also: BFSAVE

% OME Bio-Formats package for reading and converting biological file formats.
%
% Copyright (C) 2012 - 2014 Open Microscopy Environment:
%   - Board of Regents of the University of Wisconsin-Madison
%   - Glencoe Software, Inc.
%   - University of Dundee
%
% This program is free software: you can redistribute it and/or modify
% it under the terms of the GNU General Public License as
% published by the Free Software Foundation, either version 2 of the
% License, or (at your option) any later version.
%
% This program is distributed in the hope that it will be useful,
% but WITHOUT ANY WARRANTY; without even the implied warranty of
% MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
% GNU General Public License for more details.
%
% You should have received a copy of the GNU General Public License along
% with this program; if not, write to the Free Software Foundation, Inc.,
% 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.

% Not using the inputParser for first argument as it copies data
assert(isnumeric(I), 'First argument must be numeric');

% Input check
ip = inputParser;
ip.addOptional('dimensionOrder', 'XYZCT', @(x) ismember(x, getDimensionOrders()));
ip.parse(varargin{:});

% Create metadata
toInt = @(x) ome.xml.model.primitives.PositiveInteger(java.lang.Integer(x));
OMEXMLService = loci.formats.services.OMEXMLServiceImpl();
metadata = OMEXMLService.createOMEXMLMetadata();
metadata.createRoot();
metadata.setImageID('Image:0', 0);
metadata.setPixelsID('Pixels:0', 0);
metadata.setPixelsBinDataBigEndian(java.lang.Boolean.TRUE, 0, 0);

% Set dimension order
dimensionOrderEnumHandler = ome.xml.model.enums.handlers.DimensionOrderEnumHandler();
dimensionOrder = dimensionOrderEnumHandler.getEnumeration(ip.Results.dimensionOrder);
metadata.setPixelsDimensionOrder(dimensionOrder, 0);

% Set pixels type
pixelTypeEnumHandler = ome.xml.model.enums.handlers.PixelTypeEnumHandler();
if strcmp(class(I), 'single')
    pixelsType = pixelTypeEnumHandler.getEnumeration('float');
else
    pixelsType = pixelTypeEnumHandler.getEnumeration(class(I));
end
metadata.setPixelsType(pixelsType, 0);

% Read pixels size from image and set it to the metadat
sizeX = size(I, 2);
sizeY = size(I, 1);
sizeZ = size(I, find(ip.Results.dimensionOrder == 'Z'));
sizeC = size(I, find(ip.Results.dimensionOrder == 'C'));
sizeT = size(I, find(ip.Results.dimensionOrder == 'T'));
metadata.setPixelsSizeX(toInt(sizeX), 0);
metadata.setPixelsSizeY(toInt(sizeY), 0);
metadata.setPixelsSizeZ(toInt(sizeZ), 0);
metadata.setPixelsSizeC(toInt(sizeC), 0);
metadata.setPixelsSizeT(toInt(sizeT), 0);

% Set channels ID and samples per pixel
for i = 1: sizeC
    metadata.setChannelID(['Channel:0:' num2str(i-1)], 0, i-1);
    metadata.setChannelSamplesPerPixel(toInt(1), 0, i-1);
end

% Here you can edit the function and pass metadata using the adequate set methods, e.g.
% metadata.setPixelsPhysicalSizeX(ome.xml.model.primitives.PositiveFloat(java.lang.Double(.106)),0);
%
% For more information, see http://trac.openmicroscopy.org.uk/ome/wiki/BioFormats-Matlab
%
% For future versions of this function, we plan to support passing metadata as
% parameter/key value pairs

end

function dimensionOrders = getDimensionOrders()

% List all values of DimensionOrder
dimensionOrderValues = ome.xml.model.enums.DimensionOrder.values();
dimensionOrders = cell(numel(dimensionOrderValues), 1);
for i = 1 :numel(dimensionOrderValues),
    dimensionOrders{i} = char(dimensionOrderValues(i).toString());
end
end

