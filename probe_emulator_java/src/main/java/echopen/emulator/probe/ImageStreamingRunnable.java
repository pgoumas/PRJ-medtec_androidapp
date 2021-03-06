package echopen.emulator.probe;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageStreamingRunnable implements Runnable {

	private static int PORT = 7538;

	private static String IMAGE_FOLDER_PATH = "./../../data/film_2/";
	private static String IMAGE_CONFIG_FILE_NAME = "settings.txt";
	private static String IMAGE_DATA_PREFIX = "int";

	private static short NB_PIXELS_PER_LINES = 1689;

	@Override
	public void run() {
		String lIp = NetworkHelper.getCurrentBroadcastIp();

		try {
			ServerSocket lServerSocket = new ServerSocket(PORT,1,InetAddress.getByName(lIp));
			System.out.println("Server Image ready to connect");

			// Wait until a client is connecting
			Socket lSocket = lServerSocket.accept();
			System.out.println("Client connnected - Image streaming");
			DataOutputStream lSocketDataStreamer = new DataOutputStream(lSocket.getOutputStream());

			// Read probe image configuration from file and transfer it to the mobile app
			ProbeImageConfiguration lProbeImageConfiguration = readImageConfiguration();
			sendConfiguration(lSocketDataStreamer,lProbeImageConfiguration);

			// read from data folder and cache ultrasound sound raw images
			UltrasoundImageSequence lImageSequence = readImageSequence(lProbeImageConfiguration);

			// send indefinitely the ultrasound image sequence
			loopOnSendImageSequence(lSocketDataStreamer, lImageSequence);

		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/**
	 * @brief read probe image configuration from data folder
	 * @return Probe image configuration in order to build images on mobile app
	 *
	 * @throws NumberFormatException
	 * @throws IOException
	 */
	private ProbeImageConfiguration readImageConfiguration() throws NumberFormatException, IOException {
		File lConfigFile = new File(IMAGE_FOLDER_PATH + IMAGE_CONFIG_FILE_NAME);
		FileReader lFileReader = new FileReader(lConfigFile);
		BufferedReader lBuffer = new BufferedReader(lFileReader);

		short lR0 = Short.valueOf(lBuffer.readLine()); //lr0
		short lRf = Short.valueOf(lBuffer.readLine()); //lrf
		byte lDecimation = Byte.valueOf(lBuffer.readLine()); //ldecimation
		short lNlines = Short.valueOf(lBuffer.readLine()); //llineperimage
		byte lSector = Byte.valueOf(lBuffer.readLine()); //LprobeSectorAngle
		byte lMode_RP = Byte.valueOf(lBuffer.readLine()); //Lmode

		lBuffer.close();
		return new ProbeImageConfiguration(lR0, lRf, lDecimation, lNlines, lSector, lMode_RP);
	}

	/**
	 * @brief send probe image configuration to the mobile application
	 *
	 * @param iSocketDataStreamer socket data stream
	 * @param iProbeImageConfiguration probe image configuration to be sent
	 * @throws IOException
	 */
	private void sendConfiguration(DataOutputStream iSocketDataStreamer,
		ProbeImageConfiguration iProbeImageConfiguration) throws IOException {

		iSocketDataStreamer.write(iProbeImageConfiguration.getBytesArray());
		iSocketDataStreamer.flush();
	}

	/**
	 * @brief read image
	 *
	 * @param iInputFile input data file
	 * @param iNbPixelsPerLine number of pixels per line
	 * @param iNbLinesPerImage number of lines per image
	 *
	 * @return image
	 * @throws IOException
	 */

	private short[] readImage(File iInputFile, short iNbPixelsPerLine ,short iNbLinesPerImage) throws IOException{
		FileReader lFileReader = new FileReader(iInputFile);
		BufferedReader lBuffer = new BufferedReader(lFileReader);

		String lLine;
		String[] lSplitLine;
		short[] lPixelValues = new short[iNbLinesPerImage * iNbPixelsPerLine];

		// read ultrasound image data from file
		int lLineIndex = 0;
		while ((lLine = lBuffer.readLine()) != null) {
		  lSplitLine = lLine.split("\\s");
		  for(int i = 0; i < lSplitLine.length; i++ ) {
			  lPixelValues[i + lLineIndex * iNbPixelsPerLine] = Short.valueOf(lSplitLine[i]);
		  }
		  lLineIndex++;
		}

		System.out.println("Image Readed " + iInputFile.getName());

		lBuffer.close();

		return lPixelValues;
	}

	/**
	 * @brief read image sequence
	 *
	 * @param iProbeImageConfiguration probe image configuration to be read
	 *
	 * @return ultrasound image sequence
	 * @throws IOException
	 */
	private UltrasoundImageSequence readImageSequence(ProbeImageConfiguration iProbeImageConfiguration) throws IOException {
		UltrasoundImageSequence lImageSequence = new UltrasoundImageSequence(NB_PIXELS_PER_LINES ,iProbeImageConfiguration.getNLines());
	//	File lDataFile = new File(IMAGE_FOLDER_PATH + IMAGE_DATA_FILE_NAME);

		File lImageFolder = new File(IMAGE_FOLDER_PATH);
		File[] lImageFiles = lImageFolder.listFiles(new FilenameFilter() {
    	public boolean accept(File dir, String name) {
      	return name.toLowerCase().startsWith(ImageStreamingRunnable.IMAGE_DATA_PREFIX);
    	}
		});
		System.out.println("Number of images - " + lImageFiles.length);

		Pattern lPattern = Pattern.compile("\\d+");

		for (File lImageFile : lImageFiles) {
			short[] lPixelValues = readImage(lImageFile, lImageSequence.getNbPixelsPerLine(), lImageSequence.getNbLinesPerImage());
			Matcher lMatcher = lPattern.matcher(lImageFile.getName());
			Integer lImageIndex = -1;

			if(lMatcher.find()){
				lImageIndex = Integer.parseInt(lMatcher.group());
			}
			lImageSequence.addImage(lImageIndex, lPixelValues);
		}

		System.out.println("Sequence - " +  lImageSequence.getImages().size() + " images");
		return lImageSequence;
	}


	private void loopOnSendImageSequence(DataOutputStream iSocketDataStreamer, UltrasoundImageSequence iImageSequence) throws IOException{
		Boolean lIsReversed = true;

		while(true) {
			for(UltrasoundImage lImage: iImageSequence.getImages().values()) {
				sendImage(iSocketDataStreamer, lImage, lIsReversed);
				lIsReversed = !lIsReversed;
			}
		}
	}

	/**
	 * @brief send an ultrasound image through the open socket
	 *
	 * @param iSocketDataStreamer socket data streamer
	 * @param iImage ultrasound image to be sent
	 * @param iIsSendReversed boolean to decide whether the image is sent in the correct direction
	 * @throws IOException
	 */
	private void sendImage(DataOutputStream iSocketDataStreamer, UltrasoundImage iImage, Boolean iIsSendReversed) throws IOException{
		if(iIsSendReversed) {
			for(short i = (short)(iImage.getNbLinesPerImage() - 1); i >= 0; i--) {
				System.out.println("Line Sent reversed" + (i+1));
				iSocketDataStreamer.write(iImage.getNthLineAsByteArray(i));
				iSocketDataStreamer.flush();
			}
		}
		else {
			for(short i = 0; i < iImage.getNbLinesPerImage(); i++) {
				System.out.println("Line Sent " + (i+1));
				iSocketDataStreamer.write(iImage.getNthLineAsByteArray(i));
				iSocketDataStreamer.flush();
			}
		}
	}

}
