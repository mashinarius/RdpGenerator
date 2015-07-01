package com.mashinarius;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Formatter;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Crypt32;
import com.sun.jna.platform.win32.WinCrypt;
import com.sun.jna.platform.win32.WinCrypt.DATA_BLOB;
import com.sun.jna.platform.win32.WinReg;

/**
 * @author mashinarius
 * 
 *         Usage:<br>
 *         <p>
 *         /password {password} Generates encrypted RDP password and print the
 *         result to the output.<br>
 *         <i>Password must be generated at the RDP client side.<br>
 *         e.g. if you want to connect from A to B, password must be generated
 *         at the A side only.</i>
 *         <p>
 *         /file {password} {userName} {hostname} {fileName} {domain} <br>
 *         Creates {filename}.rpd file with the basic information inside
 *         (hostname, username, password, domain, screen windowed, size
 *         1280*1024)<br>
 *         Adds password to the existing RDP file
 *         <p>
 *         /open {password} {userName} {hostname} {domain} <br>
 *         Creates temporary .rdp file and open RDP connection (the username
 *         will be the prefix of the filename)
 *         <p>
 *         /force {password} {userName} {hostname} {domain} <br>
 *         Same as /open but skip warning window (untrusted publisher)
 *         <p>
 *         /update {password} {fileName} <br>
 *         Updates the file {filename}, adds new line with the encrypted
 *         password (e.g. password 51:b:{passwordhash})<br>
 * 
 */
public class RDPHelper {

	/**
	 * @param args
	 */
	public static void main(String[] args) {

		if (args.length < 2) {
			usage();
			System.exit(0);
		}

		if (args[0].equals("/password") && args.length == 2) {
			System.out.println(generatePassword(args[1]));
		} else if (args[0].equals("/file") && args.length == 5) {
			createRpdFile(args[1], args[2], args[3], args[4], ""); // no domain name was specified
		} else if (args[0].equals("/file") && args.length == 6) {
			createRpdFile(args[1], args[2], args[3], args[4], args[5]);
		} else if (args[0].equals("/open") && args.length == 4) {
			openRDPSession(false, args[1], args[2], args[3], ""); // no domain name was specified
		} else if (args[0].equals("/open") && args.length == 5) {
			openRDPSession(false, args[1], args[2], args[3], args[4]);
		} else if (args[0].equals("/force") && args.length == 4) {
			openRDPSession(true, args[1], args[2], args[3], ""); // no domain name was specified
		} else if (args[0].equals("/force") && args.length == 5) {
			openRDPSession(true, args[1], args[2], args[3], args[4]);
		} else if (args[0].equals("/update") && args.length == 3) {
			updateRDPFile(args[1], args[2]);
		} else if (args[0].equals("/winlogon") && args.length == 3) {// no domain name was specified
			winlogon(args[1], args[2], "");
		} else if (args[0].equals("/winlogon") && args.length == 4) {
			winlogon(args[1], args[2], args[3]);
		} else if (args[0].equals("/winlogoff") && args.length == 1) {
			winlogoff();
		} else if (args[0].equals("/logoff") && args.length == 2) {
			CloseRDP.closeRDP(args[1]);
		} else {
			usage();
		}
	}
	
	
	/**
	 * Disable windows automatically logon and remove default user info
	 */
	private static void winlogoff() {
		Advapi32Util.registrySetIntValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon", "AutoAdminLogon", 0);
		Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon", "DefaultDomainName", "");
		Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon", "DefaultUserName", "");
		Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon", "DefaultPassword", "");
	}

	/**
	 * Setup Windows OS automatically logon
	 * 
	 * @param userName
	 * @param userPassword
	 * @param domain
	 */
	private static void winlogon(String userName, String userPassword, String domain) {
		Advapi32Util.registrySetIntValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon", "AutoAdminLogon", 1);
		Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon", "DefaultDomainName", domain);
		Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon", "DefaultUserName", userName);
		Advapi32Util.registrySetStringValue(WinReg.HKEY_LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Winlogon", "DefaultPassword", userPassword);

	}

	/**
	 * Echo usage info
	 */
	private static void usage() {
		System.out.println("Usage:");
		System.out.println("To generate rdp password: \n\tjava -jar rdpgenerator.jar /password $PASSWORD");
		System.out.println("To create .rdp file: \n\tjava -jar rdpgenerator.jar /file $PASSWORD $USERNAME $HOST $FILENAME $DOMAIN");
		System.out.println("To start rdp session: \n\tjava -jar rdpgenerator.jar /open $PASSWORD $USERNAME $HOST $DOMAIN");
		System.out.println("To add password line to the existing file: \n\tjava -jar rdpgenerator.jar /update $PASSWORD $FILENAME");
		System.out.println("Leave $DOMAIN empty if absent");

	}

	/**
	 * Open File and add password line to the end. (<i>51:b:#passwordhash#</i>)
	 * 
	 * @param password
	 * @param filename
	 */
	private static void updateRDPFile(String password, String filename) {
		File theFile = new File(filename);
		if (!theFile.exists() || !theFile.isFile() || !theFile.canWrite()) {
			System.out.println("the file " + filename + " should exist");
		} else {
			try {
				FileWriter fstream = new FileWriter(theFile);
				BufferedWriter bufferedWriter = new BufferedWriter(fstream);
				bufferedWriter.newLine();
				bufferedWriter.write("password 51:b:" + generatePassword(password));
				bufferedWriter.newLine();
				bufferedWriter.close();
			} catch (IOException e) {
				e.printStackTrace();
			}

		}
	}

	
	private static void removeWarning(String hostname)
	{
		// Creates registry value to avoid warning window.
		//TODO change 111 (or 76 as Win7 defaults) to the correct value - it should depends on Windows OS version		

		if (!Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Terminal Server Client\\LocalDevices")) {
			Advapi32Util.registryCreateKey(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Terminal Server Client\\LocalDevices");
		}
		if (!Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Terminal Server Client\\LocalDevices", hostname)) {
			Advapi32Util.registrySetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Terminal Server Client\\LocalDevices", hostname, 111);
		}
	}
	
	/**
	 * Creates temporary file and start command <i>mstsc.exe filename</i> Adds
	 * username to the temporary filename - so RDP window could be detected with
	 * tasklist command.
	 * 
	 * @param password
	 * @param userName
	 * @param hostname
	 * @param domain
	 */
	private static void openRDPSession(boolean skipWarning, String password, String userName, String hostname, String domain) {

		if (skipWarning) {
			removeWarning(hostname);
		}

		try {
			String javaTempDir = System.getProperty("java.io.tmpdir");
			File tempDir = new File(javaTempDir);
			File theFile = null;
			if (tempDir.exists() && tempDir.isDirectory()) {
				theFile = new File(tempDir + "\\" + userName + "_temporaryFile.rdp");
			} else {
				theFile = new File(
						new URI(RDPHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI() + "\\" + userName + "_temporaryFile.rdp"));
			}
			createRpdFile(password, userName, hostname, theFile.getAbsolutePath(), domain);
			Runtime.getRuntime().exec("mstsc.exe " + theFile.getAbsolutePath());
		} catch (IOException e) {
			e.printStackTrace();
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
	}

	/**
	 * This insane method creates RPD password 1329 byte length.<br>
	 * Fills up the password with zeroes until it has 512 bytes length, encrypt,
	 * add zero to the end. Algorithm from <i>Remko Weijnen</i>.
	 * 
	 * @param initialPassword
	 * @return String encrypted password 1329 length.
	 */
	private static String generatePassword(String initialPassword) {
		try {
			byte[] passwordBytes = initialPassword.getBytes("UTF-16LE");
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			outputStream.write(passwordBytes);
			while (outputStream.size() < 512) {
				outputStream.write(0);
			}
			return (cryptPassword(outputStream.toByteArray()) + "0");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Usual method of encrypting RDP password. The encrypted result length
	 * depends on password length. Not all Windows systems supports.
	 * 
	 * @param bytes
	 * @return String encrypted password
	 */
	private static String cryptPassword(byte[] bytes) {
		DATA_BLOB pDataIn = new DATA_BLOB(bytes);
		DATA_BLOB pDataEncrypted = new DATA_BLOB();
		Crypt32.INSTANCE.CryptProtectData(pDataIn, "psw", null, null, null, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, pDataEncrypted);
		StringBuffer epwsb = new StringBuffer();
		byte[] pwdBytes = new byte[pDataEncrypted.cbData];
		pwdBytes = pDataEncrypted.getData();
		Formatter formatter = new Formatter(epwsb);
		for (final byte b : pwdBytes) {
			formatter.format("%02X", b);
		}
		formatter.close();
		return epwsb.toString();
	}

	/**
	 * Creates RDP file from the scratch
	 * 
	 * @param password
	 * @param userName
	 * @param hostname
	 * @param fileName
	 * @param domain
	 * @return created File
	 */
	private static File createRpdFile(String password, String userName, String hostname, String fileName, String domain) {
		File theFile = new File(fileName);
		if (theFile.exists()) {
			System.out.println("This file will be overwritten: " + theFile.getAbsolutePath());
		}
		FileWriter fstream;
		try {
			fstream = new FileWriter(theFile);
			BufferedWriter bufferedWriter = new BufferedWriter(fstream);
			bufferedWriter.write("full address:s:" + hostname);
			bufferedWriter.newLine();
			bufferedWriter.write("username:s:" + userName);
			bufferedWriter.newLine();
			bufferedWriter.write("password 51:b:" + generatePassword(password));
			bufferedWriter.newLine();
			bufferedWriter.write("domain:s:" + domain);
			bufferedWriter.newLine();
			bufferedWriter.write("autoreconnection enabled:i:1");
			bufferedWriter.newLine();
			bufferedWriter.write("screen mode id:i:1");// 1 - windowed	2 - fullscreen
			bufferedWriter.newLine();
			bufferedWriter.write("desktopwidth:i:1280"); //if windowed
			bufferedWriter.newLine();
			bufferedWriter.write("desktopheight:i:1024"); //if windowed
			bufferedWriter.newLine();
			bufferedWriter.write("session bpp:i:16");
			bufferedWriter.newLine();
			bufferedWriter.write("networkautodetect:i:1");
			bufferedWriter.newLine();
			bufferedWriter.write("audiomode:i:2"); //donotplay
			bufferedWriter.newLine();
			bufferedWriter.write("redirectclipboard:i:1");
			bufferedWriter.newLine();
			bufferedWriter.write("redirectprinters:i:0");
			bufferedWriter.newLine();
			bufferedWriter.write("redirectcomports:i:0");
			bufferedWriter.newLine();
			bufferedWriter.write("disable wallpaper:i:1");
			bufferedWriter.newLine();
			//bufferedWriter.write("winposstr:s:0,1,0,0,1488,756"); window start position
			//bufferedWriter.newLine();
			bufferedWriter.write("authentication level:i:0"); //If server authentication fails, connect to the computer without warning
			bufferedWriter.newLine();
			bufferedWriter.write("prompt for credentials:i:0"); //RDC will not prompt for credentials when connecting to a server that does not support server authentication.
			bufferedWriter.newLine();
			bufferedWriter.write("negotiate security layer:i:1"); //Security layer negotiation is enabled and the session is started by using x.224 encryption.
			bufferedWriter.newLine();
			bufferedWriter.write("promptcredentialonce:i:1");
			bufferedWriter.newLine();
			bufferedWriter.write("bandwidthautodetect:i:1");
			bufferedWriter.newLine();
			bufferedWriter.write("enableworkspacereconnect:i:0");
			bufferedWriter.newLine();
			bufferedWriter.close();
			fstream.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return theFile;
	}
}
