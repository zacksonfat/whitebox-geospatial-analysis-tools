/*
 * Copyright (C) 2011-2012 Dr. John Lindsay <jlindsay@uoguelph.ca>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package plugins;

import java.util.ArrayList;
import java.io.File;
import whitebox.utilities.FileUtilities;
import whitebox.geospatialfiles.ShapeFile;
import whitebox.geospatialfiles.shapefile.*;
import whitebox.interfaces.WhiteboxPlugin;
import whitebox.interfaces.WhiteboxPluginHost;

/**
 * WhiteboxPlugin is used to define a plugin tool for Whitebox GIS.
 *
 * @author Dr. John Lindsay <jlindsay@uoguelph.ca>
 */
public class RemovePolygonHoles implements WhiteboxPlugin {

    private WhiteboxPluginHost myHost = null;
    private String[] args;

    /**
     * Used to retrieve the plugin tool's name. This is a short, unique name
     * containing no spaces.
     *
     * @return String containing plugin name.
     */
    @Override
    public String getName() {
        return "RemovePolygonHoles";
    }

    /**
     * Used to retrieve the plugin tool's descriptive name. This can be a longer
     * name (containing spaces) and is used in the interface to list the tool.
     *
     * @return String containing the plugin descriptive name.
     */
    @Override
    public String getDescriptiveName() {
        return "Remove Polygon Holes";
    }

    /**
     * Used to retrieve a short description of what the plugin tool does.
     *
     * @return String containing the plugin's description.
     */
    @Override
    public String getToolDescription() {
        return "Removes the holes within a polygon feature";
    }

    /**
     * Used to identify which toolboxes this plugin tool should be listed in.
     *
     * @return Array of Strings.
     */
    @Override
    public String[] getToolbox() {
        String[] ret = {"VectorTools"};
        return ret;
    }

    /**
     * Sets the WhiteboxPluginHost to which the plugin tool is tied. This is the
     * class that the plugin will send all feedback messages, progress updates,
     * and return objects.
     *
     * @param host The WhiteboxPluginHost that called the plugin tool.
     */
    @Override
    public void setPluginHost(WhiteboxPluginHost host) {
        myHost = host;
    }

    /**
     * Used to communicate feedback pop-up messages between a plugin tool and
     * the main Whitebox user-interface.
     *
     * @param feedback String containing the text to display.
     */
    private void showFeedback(String message) {
        if (myHost != null) {
            myHost.showFeedback(message);
        } else {
            System.out.println(message);
        }
    }

    /**
     * Used to communicate a return object from a plugin tool to the main
     * Whitebox user-interface.
     *
     * @return Object, such as an output WhiteboxRaster.
     */
    private void returnData(Object ret) {
        if (myHost != null) {
            myHost.returnData(ret);
        }
    }
    private int previousProgress = 0;
    private String previousProgressLabel = "";

    /**
     * Used to communicate a progress update between a plugin tool and the main
     * Whitebox user interface.
     *
     * @param progressLabel A String to use for the progress label.
     * @param progress Float containing the progress value (between 0 and 100).
     */
    private void updateProgress(String progressLabel, int progress) {
        if (myHost != null && ((progress != previousProgress)
                || (!progressLabel.equals(previousProgressLabel)))) {
            myHost.updateProgress(progressLabel, progress);
        }
        previousProgress = progress;
        previousProgressLabel = progressLabel;
    }

    /**
     * Used to communicate a progress update between a plugin tool and the main
     * Whitebox user interface.
     *
     * @param progress Float containing the progress value (between 0 and 100).
     */
    private void updateProgress(int progress) {
        if (myHost != null && progress != previousProgress) {
            myHost.updateProgress(progress);
        }
        previousProgress = progress;
    }

    /**
     * Sets the arguments (parameters) used by the plugin.
     *
     * @param args
     */
    @Override
    public void setArgs(String[] args) {
        this.args = args.clone();
    }
    private boolean cancelOp = false;

    /**
     * Used to communicate a cancel operation from the Whitebox GUI.
     *
     * @param cancel Set to true if the plugin should be canceled.
     */
    @Override
    public void setCancelOp(boolean cancel) {
        cancelOp = cancel;
    }

    private void cancelOperation() {
        showFeedback("Operation cancelled.");
        updateProgress("Progress: ", 0);
    }
    private boolean amIActive = false;

    /**
     * Used by the Whitebox GUI to tell if this plugin is still running.
     *
     * @return a boolean describing whether or not the plugin is actively being
     * used.
     */
    @Override
    public boolean isActive() {
        return amIActive;
    }

    @Override
    public void run() {
        /*
         * This tool places the nodes (vertices) from a shapefile of polygons or
         * lines into a shapefile of Point ShapeType.
         */

        amIActive = true;
        String inputFile;
        String outputFile;
        int progress;
        int i, n;
        int numFeatures;
        int oneHundredthTotal;
        int startingPointInPart, endingPointInPart;
        double[][] vertices;
        ShapeType shapeType, outputShapeType;
        
        if (args.length <= 0) {
            showFeedback("Plugin parameters have not been set.");
            return;
        }

        inputFile = args[0];
        outputFile = args[1];
        
        // check to see that the inputHeader and outputHeader are not null.
        if ((inputFile == null) || (outputFile == null)) {
            showFeedback("One or more of the input parameters have not been set properly.");
            return;
        }

        try {
            // set up the input shapefile.
            ShapeFile input = new ShapeFile(inputFile);
            shapeType = input.getShapeType();

            // make sure that the shapetype is either a flavour of polyline or polygon.
            if (shapeType.getBaseType() != ShapeType.POLYGON) {
                showFeedback("This tool only works with shapefiles of a polygon base shape type.");
                return;
            }

            // set up the output files of the shapefile and the dbf
            outputShapeType = shapeType;

            ShapeFile output = new ShapeFile(outputFile, outputShapeType);
            output.setProjectionStringFromOtherShapefile(input);

            FileUtilities.copyFile(new File(input.getDatabaseFile()), new File(output.getDatabaseFile()));

            numFeatures = input.getNumberOfRecords();
            oneHundredthTotal = numFeatures / 100;
            n = 0;
            progress = 0;
            
            for (ShapeFileRecord record : input.records) {

                switch (shapeType) {
                    case POLYGON:
                        whitebox.geospatialfiles.shapefile.Polygon recPoly =
                                    (whitebox.geospatialfiles.shapefile.Polygon) (record.getGeometry());
                        vertices = recPoly.getPoints();
                        if (recPoly.getNumberOfHoles() == 0) {
                            // just add the polygon to the output file.
                            output.addRecord(recPoly);
                        } else {
                            // reconstruct the polygon without holes.
                            whitebox.geospatialfiles.shapefile.Polygon recPolygonOutput =
                                    (whitebox.geospatialfiles.shapefile.Polygon) (record.getGeometry());
                            ArrayList<ShapefilePoint> pnts = new ArrayList<>();
                            int numParts = recPoly.getNumParts() - recPoly.getNumberOfHoles();
                            int[] outParts = new int[numParts];
                            int[] inParts = recPoly.getParts();
                            int numPoints = recPoly.getNumPoints();
                            int numPartsIn = recPoly.getNumParts();
                            boolean[] isHole = recPoly.getPartHoleData();
                            int outPartNum = 0;
                            for (int part = 0; part < inParts.length; part++) {
                                if (!isHole[part]) {
                                    outParts[outPartNum] = pnts.size();
                                    startingPointInPart = inParts[part];
                                    if (part < numPartsIn - 1) {
                                        endingPointInPart = inParts[part + 1];
                                    } else {
                                        endingPointInPart = numPoints;
                                    }
                                    for (int p = startingPointInPart; p < endingPointInPart; p++) {
                                        pnts.add(new ShapefilePoint(vertices[p][0], vertices[p][1]));
                                    }
                                    outPartNum++;
                                }
                            }
                            PointsList pl = new PointsList(pnts);
                            whitebox.geospatialfiles.shapefile.Polygon wbPoly = new whitebox.geospatialfiles.shapefile.Polygon(outParts, pl.getPointsArray());
                            output.addRecord(wbPoly);
                        }
                        break;
                    case POLYGONZ:
                        PolygonZ recPolyZ = (PolygonZ) (record.getGeometry());
                        vertices = recPolyZ.getPoints();
                        if (recPolyZ.getNumberOfHoles() == 0) {
                            // just add the polygon to the output file.
                            output.addRecord(recPolyZ);
                        } else {
                            // reconstruct the polygon without holes.
                            ArrayList<ShapefilePoint> pnts = new ArrayList<ShapefilePoint>();
                            int numParts = recPolyZ.getNumParts() - recPolyZ.getNumberOfHoles();
                            int[] outParts = new int[numParts];
                            int[] inParts = recPolyZ.getParts();
                            int numPoints = recPolyZ.getNumPoints();
                            int numPartsIn = recPolyZ.getNumParts();
                            boolean[] isHole = recPolyZ.getPartHoleData();
                            int outPartNum = 0;
                            for (int part = 0; part < inParts.length; part++) {
                                if (!isHole[part]) {
                                    outParts[outPartNum] = pnts.size();
                                    startingPointInPart = inParts[part];
                                    if (part < numPartsIn - 1) {
                                        endingPointInPart = inParts[part + 1];
                                    } else {
                                        endingPointInPart = numPoints;
                                    }
                                    for (int p = startingPointInPart; p < endingPointInPart; p++) {
                                        pnts.add(new ShapefilePoint(vertices[p][0], vertices[p][1]));
                                    }
                                    outPartNum++;
                                }
                            }
                            PointsList pl = new PointsList(pnts);
                            // z data
                            double[] zArray = recPolyZ.getzArray();
                            double[] zArrayOut = new double[pnts.size()];
                            int j = 0;
                            for (int part = 0; part < inParts.length; part++) {
                                if (!isHole[part]) {
                                    startingPointInPart = inParts[part];
                                    if (part < numPartsIn - 1) {
                                        endingPointInPart = inParts[part + 1];
                                    } else {
                                        endingPointInPart = numPoints;
                                    }
                                    for (int p = startingPointInPart; p < endingPointInPart; p++) {
                                        zArrayOut[j] = zArray[p];
                                        j++;
                                    }
                                }
                            }
                            // m data
                            double[] mArray = recPolyZ.getmArray();
                            double[] mArrayOut = new double[pnts.size()];
                            j = 0;
                            for (int part = 0; part < inParts.length; part++) {
                                if (!isHole[part]) {
                                    startingPointInPart = inParts[part];
                                    if (part < numPartsIn - 1) {
                                        endingPointInPart = inParts[part + 1];
                                    } else {
                                        endingPointInPart = numPoints;
                                    }
                                    for (int p = startingPointInPart; p < endingPointInPart; p++) {
                                        mArrayOut[j] = mArray[p];
                                        j++;
                                    }
                                }
                            }       
                            PolygonZ wbPoly = new PolygonZ(outParts, pl.getPointsArray(), zArrayOut, mArrayOut);
                            output.addRecord(wbPoly);
                        }
                        break;
                    case POLYGONM:
                        PolygonM recPolyM = (PolygonM) (record.getGeometry());
                        vertices = recPolyM.getPoints();
                        if (recPolyM.getNumberOfHoles() == 0) {
                            // just add the polygon to the output file.
                            output.addRecord(recPolyM);
                        } else {
                            // reconstruct the polygon without holes.
                            ArrayList<ShapefilePoint> pnts = new ArrayList<>();
                            int numParts = recPolyM.getNumParts() - recPolyM.getNumberOfHoles();
                            int[] outParts = new int[numParts];
                            int[] inParts = recPolyM.getParts();
                            int numPoints = recPolyM.getNumPoints();
                            int numPartsIn = recPolyM.getNumParts();
                            boolean[] isHole = recPolyM.getPartHoleData();
                            int outPartNum = 0;
                            for (int part = 0; part < inParts.length; part++) {
                                if (!isHole[part]) {
                                    outParts[outPartNum] = pnts.size();
                                    startingPointInPart = inParts[part];
                                    if (part < numPartsIn - 1) {
                                        endingPointInPart = inParts[part + 1];
                                    } else {
                                        endingPointInPart = numPoints;
                                    }
                                    for (int p = startingPointInPart; p < endingPointInPart; p++) {
                                        pnts.add(new ShapefilePoint(vertices[p][0], vertices[p][1]));
                                    }
                                    outPartNum++;
                                }
                            }
                            PointsList pl = new PointsList(pnts);
                            
                            // m data
                            double[] mArray = recPolyM.getmArray();
                            double[] mArrayOut = new double[pnts.size()];
                            int j = 0;
                            for (int part = 0; part < inParts.length; part++) {
                                if (!isHole[part]) {
                                    startingPointInPart = inParts[part];
                                    if (part < numPartsIn - 1) {
                                        endingPointInPart = inParts[part + 1];
                                    } else {
                                        endingPointInPart = numPoints;
                                    }
                                    for (int p = startingPointInPart; p < endingPointInPart; p++) {
                                        mArrayOut[j] = mArray[p];
                                        j++;
                                    }
                                }
                            }
                            PolygonM wbPoly = new PolygonM(outParts, pl.getPointsArray(), mArrayOut);
                            output.addRecord(wbPoly);
                        }
                        break;
                }
                n++;
                if (n >= oneHundredthTotal) {
                    n = 0;
                    if (cancelOp) {
                        cancelOperation();
                        return;
                    }
                    progress++;
                    updateProgress(progress);
                }
            }

            output.write();

            // returning a header file string displays the image.
            updateProgress("Displaying vector: ", 0);
            returnData(outputFile);


        } catch (OutOfMemoryError oe) {
            myHost.showFeedback("An out-of-memory error has occurred during operation.");
        } catch (Exception e) {
            myHost.showFeedback("An error has occurred during operation. See log file for details.");
            myHost.logException("Error in " + getDescriptiveName(), e);
        } finally {
            updateProgress("Progress: ", 0);
            // tells the main application that this process is completed.
            amIActive = false;
            myHost.pluginComplete();
        }

    }

//    //This method is only used during testing.
//    public static void main(String[] args) {
//        args = new String[2];
//        //args[0] = "/Users/johnlindsay/Documents/Research/Contracts/NRCan 2012/65E UTM.shp";
//        //args[1] = "/Users/johnlindsay/Documents/Research/Contracts/NRCan 2012/tmp3.shp";
//        //args[0] = "/Users/johnlindsay/Documents/Data/ShapeFiles/Water_Body_rmow.shp";
//        //args[1] = "/Users/johnlindsay/Documents/Data/ShapeFiles/tmp4.shp";
//        args[0] = "/Users/johnlindsay/Documents/Research/Contracts/NRCan 2012/Data/alllakesutmdissolve.shp";
//        args[1] = "/Users/johnlindsay/Documents/Research/Contracts/NRCan 2012/Data/tmp1.shp";
//        
//        RemovePolygonHoles rph = new RemovePolygonHoles();
//        rph.setArgs(args);
//        rph.run();
//    }
}