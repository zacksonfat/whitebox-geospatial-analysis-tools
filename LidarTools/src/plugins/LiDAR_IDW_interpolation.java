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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import whitebox.geospatialfiles.LASReader;
import whitebox.geospatialfiles.LASReader.PointRecColours;
import whitebox.geospatialfiles.LASReader.PointRecord;
import whitebox.geospatialfiles.MappedWhiteboxRaster;
import whitebox.geospatialfiles.WhiteboxRaster;
import whitebox.interfaces.WhiteboxPlugin;
import whitebox.interfaces.WhiteboxPluginHost;
import whitebox.structures.KdTree;
import whitebox.parallel.Parallel;

/**
 * WhiteboxPlugin is used to define a plugin tool for Whitebox GIS.
 * 
 * @author Dr. John Lindsay <jlindsay@uoguelph.ca>
 */
public class LiDAR_IDW_interpolation implements WhiteboxPlugin {

    private WhiteboxPluginHost myHost = null;
    private String[] args;
    private long numPointFiles = 0; //Made to be accessable by whole class for progress bar
    private Integer numFilesCompleted = 0; //For progress bar
    private boolean message;
    
    /**
     * Used to retrieve the plugin tool's name. This is a short, unique name
     * containing no spaces.
     *
     * @return String containing plugin name.
     */
    @Override
    public String getName() {
        return "LiDAR_IDW_interpolation";
    }

    /**
     * Used to retrieve the plugin tool's descriptive name. This can be a longer
     * name (containing spaces) and is used in the interface to list the tool.
     *
     * @return String containing the plugin descriptive name.
     */
    @Override
    public String getDescriptiveName() {
        return "IDW Interpolation (LiDAR)";
    }

    /**
     * Used to retrieve a short description of what the plugin tool does.
     *
     * @return String containing the plugin's description.
     */
    @Override
    public String getToolDescription() {
        return "Interpolates LiDAR point data from text files using an "
                + "inverse-distance to a weight scheme.";
    }

    /**
     * Used to identify which toolboxes this plugin tool should be listed in.
     *
     * @return Array of Strings.
     */    
    @Override
    public String[] getToolbox() {
        String[] ret = {"LidarTools"};
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
        if (message) {
            message = false;
            showFeedback("Operation cancelled.");
            updateProgress("Progress: ", 0);
        }
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

    private ArrayList<String> sortFiles(String[] pointFiles, long numOfPointFiles){
        ArrayList<String> sortedNames  = new ArrayList<String>();
        ArrayList<Long> sortedSizes  = new ArrayList<Long>();
        long currentSize = 0;
        int j,i;
        
        LASReader las;  
        for (i = 0; i < numOfPointFiles; i++) {
            
            las = new LASReader(pointFiles[i]);
            currentSize = las.getNumPointRecords();   
            
            for(j = 0; j < i; j++){
                if(currentSize >= sortedSizes.get(j)){
                    sortedNames.add(j, pointFiles[i]);
                    sortedSizes.add(j, currentSize);
                    break;
                }
            }
            
            //We didn't place it yet
            if (j == i){
                sortedNames.add(pointFiles[i]);
                sortedSizes.add(currentSize);
            }
        }
        
        return sortedNames;
    }
    
    @Override
    @SuppressWarnings("empty-statement")
    public void run() {
        amIActive = true;

        String inputFilesString;
        String[] pointFiles;
        
        long start = System.nanoTime();
                
        // get the arguments
        if (args.length <= 0) {
            showFeedback("Plugin parameters have not been set.");
            return;
        }
        
        //int threads = Parallel.getPluginProcessors();
        int threads = Parallel.getPluginProcessors();
        System.out.println("Number of threads: " + threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
                
        inputFilesString = args[0];
        
        // check to see that the inputHeader and outputHeader are not null.
        if ((inputFilesString.length() <= 0)) {
            showFeedback("One or more of the input parameters have not been set properly.");
            return;
        }

        try {
            pointFiles = inputFilesString.split(";");
            numPointFiles = pointFiles.length;
            
            // Sort the file sizes to run the largest of them all first
            ArrayList<String> sortedNames = sortFiles(pointFiles, numPointFiles);

            for (int j = 0; j < numPointFiles; j++) {
                if(j == 0){
                    pool.execute(new liDARIDWInterWork(sortedNames.get(j), true));
                }
                else{
                    pool.execute(new liDARIDWInterWork(sortedNames.get(j), false));
                }
            }
        
            pool.shutdown();
            while(!pool.awaitTermination(1, TimeUnit.SECONDS)) { };
            
        } catch (OutOfMemoryError oe) {
            showFeedback("The Java Virtual Machine (JVM) is out of memory");
        } catch (Exception e) {
            showFeedback(e.getMessage());
        } finally {
            updateProgress("Progress: ", 0);
            // tells the main application that this process is completed.
            amIActive = false;
            myHost.pluginComplete();
            
        }
        
        long end = System.nanoTime();
        System.out.println("Time Elapsed: " + (end-start)/1000000000.0);
    
        // just in case
        pool.shutdown();
    }
    
    private class liDARIDWInterWork implements Runnable {
        
        private String pointFile;
        boolean shouldDisplay;
        
        public liDARIDWInterWork(String pointFile, boolean shouldDisplay) {
            this.pointFile = pointFile;
            this.shouldDisplay = shouldDisplay;
        }
        
        @Override
        public void run() {
            String outputHeader = null;
            int row, col;
            int nrows, ncols;
            double x, y;
            double z = 0;
            int a, i;
            int progress = 0;
            int numPoints = 0;
            int numPointsToUse = 8;
            double dist = 0;
            double weight = 1;
            double maxDist = Double.POSITIVE_INFINITY;
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            double north, south, east, west;
            double resolution = 1;
            String str1 = null;
            FileWriter fw = null;
            BufferedWriter bw = null;
            PrintWriter out = null;
            List<KdTree.Entry<Double>> results;
            double sumWeights;
            double noData = -32768;
            double northing, easting;
            String whatToInterpolate = "";
            String returnNumberToInterpolate = "all points";
            String suffix = "";
            boolean excludeNeverClassified = false;
            boolean excludeUnclassified = false;
            boolean excludeBareGround = false;
            boolean excludeLowVegetation = false;
            boolean excludeMediumVegetation = false;
            boolean excludeHighVegetation = false;
            boolean excludeBuilding = false;
            boolean excludeLowPoint = false;
            //boolean excludeHighPoint = false;
            boolean excludeModelKeyPoint = false;
            boolean excludeWater = false;

            // get the arguments
            if (args.length <= 0) {
                showFeedback("Plugin parameters have not been set.");
                return;
            }
            
            suffix = " " + args[1].trim();
            whatToInterpolate = args[2].toLowerCase();
            returnNumberToInterpolate = args[3].toLowerCase();
            weight = Double.parseDouble(args[4]);
            if (!args[5].equalsIgnoreCase("not specified")) {
                maxDist = Double.parseDouble(args[5]);
            }
            numPointsToUse = Integer.parseInt(args[6]);
            resolution = Double.parseDouble(args[7]);
            excludeNeverClassified = Boolean.parseBoolean(args[8]);
            excludeUnclassified = Boolean.parseBoolean(args[9]);
            excludeBareGround = Boolean.parseBoolean(args[10]);
            excludeLowVegetation = Boolean.parseBoolean(args[11]);
            excludeMediumVegetation = Boolean.parseBoolean(args[12]);
            excludeHighVegetation = Boolean.parseBoolean(args[13]);
            excludeBuilding = Boolean.parseBoolean(args[14]);
            excludeLowPoint = Boolean.parseBoolean(args[15]);
            //excludeHighPoint = Boolean.parseBoolean(args[16]);
            excludeModelKeyPoint = Boolean.parseBoolean(args[16]);
            excludeWater = Boolean.parseBoolean(args[17]);
            
            try {
            
                boolean[] classValuesToExclude = new boolean[32]; // there can be up to 32 different classes in future versions

                if (excludeNeverClassified) { classValuesToExclude[0] = true; }
                if (excludeUnclassified) { classValuesToExclude[1] = true; }
                if (excludeBareGround) { classValuesToExclude[2] = true; }
                if (excludeLowVegetation) { classValuesToExclude[3] = true; }
                if (excludeMediumVegetation) { classValuesToExclude[4] = true; }
                if (excludeHighVegetation) { classValuesToExclude[5] = true; }
                if (excludeBuilding) { classValuesToExclude[6] = true; }
                if (excludeLowPoint) { classValuesToExclude[7] = true; }
                if (excludeModelKeyPoint) { classValuesToExclude[8] = true; }
                if (excludeWater) { classValuesToExclude[9] = true; }

                long numPointsInFile = 0;

                if (maxDist < Double.POSITIVE_INFINITY) {
                    maxDist = maxDist * maxDist;
                }

                PointRecord point;
                PointRecColours pointColours;
                double[] entry;

                LASReader las = new LASReader(pointFile);
                numPointsInFile = las.getNumPointRecords();
                
                // first count how many valid points there are.
                numPoints = 0;
                for (a = 0; a < numPointsInFile; a++) {
                    point = las.getPointRecord(a);
                    if (returnNumberToInterpolate.equals("all points")) {
                        if (!point.isPointWithheld() && 
                                !(classValuesToExclude[point.getClassification()])) {
                            numPoints++;
                        }
                    } else if (returnNumberToInterpolate.equals("first return")) {
                        if (!point.isPointWithheld() && 
                                !(classValuesToExclude[point.getClassification()]) &&
                                point.getReturnNumber() == 1) {
                            numPoints++;
                        }
                    } else { // if (returnNumberToInterpolate.equals("last return")) {
                        if (!point.isPointWithheld() && 
                                !(classValuesToExclude[point.getClassification()]) &&
                                point.getReturnNumber() == point.getNumberOfReturns()) {
                            numPoints++;
                        }
                    }
                }

                // now read the valid points into the k-dimensional tree.

                minX = Double.POSITIVE_INFINITY;
                maxX = Double.NEGATIVE_INFINITY;
                minY = Double.POSITIVE_INFINITY;
                maxY = Double.NEGATIVE_INFINITY;

                KdTree<Double> pointsTree = new KdTree.SqrEuclid<Double>(2, new Integer(numPoints));

                // read the points in
                if (returnNumberToInterpolate.equals("all points")) {
                    for (a = 0; a < numPointsInFile; a++) {
                        point = las.getPointRecord(a);
                        if (!point.isPointWithheld()
                                && !(classValuesToExclude[point.getClassification()])) {
                            x = point.getX();
                            y = point.getY();
                            if (whatToInterpolate.equals("z (elevation)")) {
                                z = point.getZ();
                            } else if (whatToInterpolate.equals("intensity")) {
                                z = point.getIntensity();
                            } else if (whatToInterpolate.equals("classification")) {
                                z = point.getClassification();
                            } else if (whatToInterpolate.equals("scan angle")) {
                                z = point.getScanAngle();
                            } else if (whatToInterpolate.equals("rgb data")) {
                                pointColours = las.getPointRecordColours(a);
                                z = (double)((255 << 24) | (pointColours.getBlue() 
                                        << 16) | (pointColours.getGreen() << 8) | 
                                        pointColours.getRed());
                            }

                            entry = new double[]{y, x};
                            pointsTree.addPoint(entry, z);

                            if (x < minX) {
                                minX = x;
                            }
                            if (x > maxX) {
                                maxX = x;
                            }
                            if (y < minY) {
                                minY = y;
                            }
                            if (y > maxY) {
                                maxY = y;
                            }
                        }
                    }
                } else if (returnNumberToInterpolate.equals("first return")) {
                    for (a = 0; a < numPointsInFile; a++) {
                        point = las.getPointRecord(a);
                        if (!point.isPointWithheld()
                                && !(classValuesToExclude[point.getClassification()]) &&
                                point.getReturnNumber() == 1) {
                            x = point.getX();
                            y = point.getY();
                            if (whatToInterpolate.equals("z (elevation)")) {
                                z = point.getZ();
                            } else if (whatToInterpolate.equals("intensity")) {
                                z = point.getIntensity();
                            } else if (whatToInterpolate.equals("classification")) {
                                z = point.getClassification();
                            } else if (whatToInterpolate.equals("scan angle")) {
                                z = point.getScanAngle();
                            } else if (whatToInterpolate.equals("rgb data")) {
                                pointColours = las.getPointRecordColours(a);
                                z = (double)((255 << 24) | (pointColours.getBlue() 
                                        << 16) | (pointColours.getGreen() << 8) | 
                                        pointColours.getRed());
                            }

                            entry = new double[]{y, x};
                            pointsTree.addPoint(entry, z);

                            if (x < minX) {
                                minX = x;
                            }
                            if (x > maxX) {
                                maxX = x;
                            }
                            if (y < minY) {
                                minY = y;
                            }
                            if (y > maxY) {
                                maxY = y;
                            }
                        }
                    }
                } else { // if (returnNumberToInterpolate.equals("last return")) {
                    for (a = 0; a < numPointsInFile; a++) {
                        point = las.getPointRecord(a);
                        if (!point.isPointWithheld()
                                && !(classValuesToExclude[point.getClassification()]) &&
                                point.getReturnNumber() == point.getNumberOfReturns()) {
                            x = point.getX();
                            y = point.getY();
                            if (whatToInterpolate.equals("z (elevation)")) {
                                z = point.getZ();
                            } else if (whatToInterpolate.equals("intensity")) {
                                z = point.getIntensity();
                            } else if (whatToInterpolate.equals("classification")) {
                                z = point.getClassification();
                            } else if (whatToInterpolate.equals("scan angle")) {
                                z = point.getScanAngle();
                            } else if (whatToInterpolate.equals("rgb data")) {
                                pointColours = las.getPointRecordColours(a);
                                z = (double)((255 << 24) | (pointColours.getBlue() 
                                        << 16) | (pointColours.getGreen() << 8) | 
                                        pointColours.getRed());
                            }

                            entry = new double[]{y, x};
                            pointsTree.addPoint(entry, z);

                            if (x < minX) {
                                minX = x;
                            }
                            if (x > maxX) {
                                maxX = x;
                            }
                            if (y < minY) {
                                minY = y;
                            }
                            if (y > maxY) {
                                maxY = y;
                            }
                        }
                    }
                }

                outputHeader = pointFile.replace(".las", suffix + ".dep");

                // see if the output files already exist, and if so, delete them.
                if ((new File(outputHeader)).exists()) {
                    (new File(outputHeader)).delete();
                    (new File(outputHeader.replace(".dep", ".tas"))).delete();
                }

                // What are north, south, east, and west and how many rows and 
                // columns should there be?
                west = minX - 0.5 * resolution;
                north = maxY + 0.5 * resolution;
                nrows = (int)(Math.ceil((north - minY) / resolution));
                ncols = (int)(Math.ceil((maxX - west) / resolution));
                south = north - nrows * resolution;
                east = west + ncols * resolution;

                // create the whitebox header file.
                fw = new FileWriter(outputHeader, false);
                bw = new BufferedWriter(fw);
                out = new PrintWriter(bw, true);

                str1 = "Min:\t" + Double.toString(Integer.MAX_VALUE);
                out.println(str1);
                str1 = "Max:\t" + Double.toString(Integer.MIN_VALUE);
                out.println(str1);
                str1 = "North:\t" + Double.toString(north);
                out.println(str1);
                str1 = "South:\t" + Double.toString(south);
                out.println(str1);
                str1 = "East:\t" + Double.toString(east);
                out.println(str1);
                str1 = "West:\t" + Double.toString(west);
                out.println(str1);
                str1 = "Cols:\t" + Integer.toString(ncols);
                out.println(str1);
                str1 = "Rows:\t" + Integer.toString(nrows);
                out.println(str1);
                str1 = "Data Type:\t" + "float";
                out.println(str1);
                str1 = "Z Units:\t" + "not specified";
                out.println(str1);
                str1 = "XY Units:\t" + "not specified";
                out.println(str1);
                str1 = "Projection:\t" + "not specified";
                out.println(str1);
                if (!whatToInterpolate.equals("rgb data")) {
                   str1 = "Data Scale:\tcontinuous"; 
                } else {
                   str1 = "Data Scale:\trgb"; 
                }
                out.println(str1);
                if (whatToInterpolate.equals("rgb data")) {
                    str1 = "Preferred Palette:\t" + "rgb.pal";
                } else if (whatToInterpolate.equals("intensity")) {
                    str1 = "Preferred Palette:\t" + "grey.pal";
                } else {
                    str1 = "Preferred Palette:\t" + "spectrum.pal";
                }
                out.println(str1);
                str1 = "NoData:\t" + noData;
                out.println(str1);
                if (java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.LITTLE_ENDIAN) {
                    str1 = "Byte Order:\t" + "LITTLE_ENDIAN";
                } else {
                    str1 = "Byte Order:\t" + "BIG_ENDIAN";
                }
                out.println(str1);

                out.close();

                // Create the whitebox raster object.
                //WhiteboxRaster image = new WhiteboxRaster(outputHeader, "rw");
                MappedWhiteboxRaster image = new MappedWhiteboxRaster(outputHeader, "rw", true);
                
                double halfResolution = resolution / 2;
                if (!whatToInterpolate.equals("rgb data")) {
                    for (row = 0; row < nrows; row++) {
                        for (col = 0; col < ncols; col++) {
                            easting = (col * resolution) + (west + halfResolution);
                            northing = (north - halfResolution) - (row * resolution);
                            entry = new double[]{northing, easting};
                            results = pointsTree.nearestNeighbor(entry, numPointsToUse, false);
                            sumWeights = 0;
                            for (i = 0; i < results.size(); i++) {
                                if ((results.get(i).distance > 0) && (results.get(i).distance < maxDist)) {
                                    dist = Math.pow(Math.sqrt(results.get(i).distance), weight);
                                    sumWeights += 1 / dist;
                                } else if (results.get(i).distance == 0) {
                                    break;
                                }
                            }
                            if (sumWeights > 0) {
                                z = 0;
                                for (i = 0; i < results.size(); i++) {
                                    if ((results.get(i).distance > 0) && (results.get(i).distance < maxDist)) {
                                        dist = 1 / Math.pow(Math.sqrt(results.get(i).distance), weight);
                                        z += (dist * results.get(i).value) / sumWeights;
                                    } else if (results.get(i).distance == 0) {
                                        z = results.get(i).value;
                                        break;
                                    }
                                }
                                image.setValue(row, col, z);
                            } else {
                                image.setValue(row, col, noData);
                            }
                        }
                        if (cancelOp) {
                            cancelOperation();
                            return;
                        }
                    }
                } else { // rgb is being interpolated
                    double r, g, b;
                    double zR, zG, zB;
                    double val;
                    for (row = 0; row < nrows; row++) {
                        for (col = 0; col < ncols; col++) {
                            easting = (col * resolution) + (west + halfResolution);
                            northing = (north - halfResolution) - (row * resolution);
                            entry = new double[]{northing, easting};
                            results = pointsTree.nearestNeighbor(entry, numPointsToUse, false);
                            sumWeights = 0;
                            for (i = 0; i < results.size(); i++) {
                                if ((results.get(i).distance > 0) && (results.get(i).distance < maxDist)) {
                                    dist = Math.pow(Math.sqrt(results.get(i).distance), weight);
                                    sumWeights += 1 / dist;
                                } else if (results.get(i).distance == 0) {
                                    break;
                                }
                            }
                            if (sumWeights > 0) {
                                z = 0;
                                zR = 0;
                                zG = 0;
                                zB = 0;
                                for (i = 0; i < results.size(); i++) {
                                    if ((results.get(i).distance > 0) && (results.get(i).distance < maxDist)) {
                                        val = results.get(i).value;
                                        r = (double)((int)val & 0xFF);
                                        g = (double)(((int)val >> 8) & 0xFF);
                                        b = (double)(((int)val >> 16) & 0xFF);

                                        dist = 1 / Math.pow(Math.sqrt(results.get(i).distance), weight);
                                        zR += (dist * r) / sumWeights;
                                        zG += (dist * g) / sumWeights;
                                        zB += (dist * b) / sumWeights;
                                    } else if (results.get(i).distance == 0) {
                                        z = results.get(i).value;
                                        break;
                                    }
                                }
                                z = (double)((255 << 24) | ((int)zB << 16) | ((int)zG << 8) | (int)zR);
                                image.setValue(row, col, z);
                            } else {
                                image.setValue(row, col, noData);
                            }
                        }
                        if (cancelOp) {
                            cancelOperation();
                            return;
                        }
                    }
                }
                image.addMetadataEntry("Created by the "
                        + getDescriptiveName() + " tool.");
                image.addMetadataEntry("Created on " + new Date());

                image.close();
                
                //Udpate the progress bar
                synchronized(numFilesCompleted){
                    numFilesCompleted +=1;
                    progress = (int) (100d * numFilesCompleted / numPointFiles);
                    updateProgress("Progress", progress);
                }
                
                //Only display the last of all of the LAS file interpolations
                if(shouldDisplay == true){
                    returnData(pointFile.replace(".las", suffix + ".dep"));
                }

            } catch (OutOfMemoryError oe) {
                showFeedback("The Java Virtual Machine (JVM) is out of memory");
            } catch (Exception e) {
                System.out.println(e);
                showFeedback(e.getLocalizedMessage());
            } finally {
                // tells the main application that this process is completed.
                amIActive = false;
                myHost.pluginComplete();
            }
            
        }    
    }
      
//    // this is only used for debugging the tool
//    public static void main(String[] args) {
//        LiDAR_IDW_interpolation nn = new LiDAR_IDW_interpolation();
//        args = new String[19];
//        args[0] = "/Users/johnlindsay/Documents/Data/u_5565073175.las";
//        //args[0] = "/Users/johnlindsay/Documents/Data/u_5565073250.las";
//        args[1] = "IDW last return";
//        args[2] = "Z (elevation)"; // "intensity";
//        args[3] = "last return";
//        args[4] = "1"; // weight
//        args[5] = "4"; // max dist
//        args[6] = "8"; // num points to use
//        args[7] = "1"; // resolution
//        nn.setArgs(args);
//        nn.run();
//        
//    }
}