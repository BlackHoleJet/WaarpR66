/**
 * This file is part of Waarp Project.
 * 
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author tags. See the
 * COPYRIGHT.txt in the distribution for a full listing of individual contributors.
 * 
 * All Waarp Project is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License as published by the Free Software Foundation, either version 3 of
 * the License, or (at your option) any later version.
 * 
 * Waarp is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with Waarp . If not, see
 * <http://www.gnu.org/licenses/>.
 */
package org.waarp.openr66.client;

import java.io.File;
import java.io.FileFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.waarp.common.database.exception.WaarpDatabaseException;
import org.waarp.common.filemonitor.FileMonitor;
import org.waarp.common.filemonitor.FileMonitorCommandFactory;
import org.waarp.common.filemonitor.FileMonitorCommandRunnableFuture;
import org.waarp.common.filemonitor.RegexFileFilter;
import org.waarp.common.filemonitor.FileMonitor.FileItem;
import org.waarp.common.logging.WaarpInternalLogger;
import org.waarp.common.logging.WaarpInternalLoggerFactory;
import org.waarp.common.logging.WaarpSlf4JLoggerFactory;
import org.waarp.common.utility.WaarpThreadFactory;
import org.waarp.common.xml.XmlDecl;
import org.waarp.common.xml.XmlHash;
import org.waarp.common.xml.XmlType;
import org.waarp.common.xml.XmlUtil;
import org.waarp.common.xml.XmlValue;
import org.waarp.openr66.configuration.FileBasedConfiguration;
import org.waarp.openr66.context.ErrorCode;
import org.waarp.openr66.context.R66Result;
import org.waarp.openr66.context.task.SpooledInformTask;
import org.waarp.openr66.database.DbConstant;
import org.waarp.openr66.database.data.DbRule;
import org.waarp.openr66.database.data.DbTaskRunner;
import org.waarp.openr66.protocol.configuration.Configuration;
import org.waarp.openr66.protocol.configuration.Messages;
import org.waarp.openr66.protocol.localhandler.packet.BusinessRequestPacket;
import org.waarp.openr66.protocol.networkhandler.NetworkTransaction;
import org.waarp.openr66.protocol.utils.ChannelUtils;
import org.waarp.openr66.protocol.utils.R66Future;

/**
 * Direct Transfer from a client with or without database connection 
 * or Submit Transfer from a client with database connection 
 * to transfer files from a spooled directory to possibly multiple hosts at once.<br>
 * -to Hosts will have to be separated by ','.<br>
 * -rule Rule to be used to send files to partners<br>
 * <br>
 * Mandatory additional elements:<br>
 * -directory source (directory to spooled on ; many directories can be specified using a comma separated list as "directory1,directory2,directory3")<br>
 * -statusfile file (file to use as permanent status (if process is killed or aborts))<br>
 * -stopfile file (file when created will stop the dameon)<br>
 * Other options:<br>
 * -info info to be send with the file as filetransfer information<br>
 * -md5 for md5 option<br>
 * -block size for block size specification<br>
 * -nolog to prevent saving action locally<br>
 * -regex regex (regular expression to filter file names from directory source)<br>
 * -elapse elapse (elapse time in ms > 100 ms between 2 checks of the directory)<br>
 * -submit (to submit only: default: only one between submit and direct is allowed)<br>
 * -direct (to directly transfer only: only one between submit and direct is allowed)<br>
 * -recursive (to scan recursively from the root)<br>
 * -waarp WaarpHosts (seperated by ',') to inform of running spooled directory (information stays in memory of Waarp servers, not in database)<br>
 * -name name to be used as name in list printing in Waarp servers. Note this name must be unique globally.<br>
 * -elapseWaarp elapse to specify a specific timing > 1000ms between to information sent to Waarp servers (default: 5000ms)<br>
 * -parallel to allow (default) parallelism in send actions (submit are always sequential)<br>
 * -sequential to not allow parallelism in send actions (submit are always sequential)<br>
 * -limitParallel limit to specify the number of concurrent actions in -direct mode and if parallel mode is active<br>
 * 
 * @author Frederic Bregier
 * 
 */
public class SpooledDirectoryTransfer implements Runnable {
	/**
	 * Internal Logger
	 */
	static protected volatile WaarpInternalLogger logger;

	protected static String _INFO_ARGS = 
			Messages.getString("SpooledDirectoryTransfer.0"); //$NON-NLS-1$
	
	
	protected static final String NO_INFO_ARGS = "noinfo";

	protected final R66Future future;
	
	public final String name;

	protected final String directory;

	protected final String statusFile;

	protected final String stopFile;

	protected final String rulename;

	protected final String fileinfo;

	protected final boolean isMD5;

	protected final String remoteHosts;

	protected final String regexFilter;

	protected final String waarpHosts;

	protected final int blocksize;

	protected final long elapseTime;
	
	protected final long elapseWaarpTime;

	protected final boolean parallel;
	
	protected final int limitParallelTasks;

	protected final boolean submit;
	
	protected final boolean nolog;
	
	protected final boolean recurs;
	
	protected final NetworkTransaction networkTransaction;
	
	protected String[] allrhosts = null;
	
	protected FileMonitor monitor = null;
	
	public long sent = 0;
	public long error = 0;
	
	
	/**
	 * @param future
	 * @param name
	 * @param directory
	 * @param statusfile
	 * @param stopfile
	 * @param rulename
	 * @param fileinfo
	 * @param isMD5
	 * @param remoteHosts
	 * @param blocksize
	 * @param regex
	 * @param elapse
	 * @param submit
	 * @param nolog
	 * @param recursive
	 * @param elapseWaarp
	 * @param parallel
	 * @param waarphost
	 * @param networkTransaction
	 */
	public SpooledDirectoryTransfer(R66Future future, String name, String directory, 
			String statusfile, String stopfile, String rulename,
			String fileinfo, boolean isMD5, 
			String remoteHosts, int blocksize, String regex,
			long elapse, boolean submit, boolean nolog, boolean recursive, 
			long elapseWaarp, boolean parallel, int limitParallel,
			String waarphost, NetworkTransaction networkTransaction) {
		if (logger == null) {
			logger = WaarpInternalLoggerFactory.getLogger(SpooledDirectoryTransfer.class);
		}
		this.future = future;
		this.name = name;
		this.directory = directory;
		this.statusFile = statusfile;
		this.stopFile = stopfile;
		this.rulename = rulename;
		this.fileinfo = fileinfo;
		this.isMD5 = isMD5;
		this.remoteHosts = remoteHosts;
		this.blocksize = blocksize;
		this.regexFilter = regex;
		this.elapseTime = elapse;
		this.submit = submit;
		this.nolog = nolog && (!submit);
		AbstractTransfer.nolog = this.nolog;
		this.recurs = recursive;
		this.elapseWaarpTime = elapseWaarp;
		this.limitParallelTasks = limitParallel;
		if (this.submit) {
			this.parallel = false;
		} else {
			this.parallel = parallel;
		}
		this.waarpHosts = waarphost;
		this.networkTransaction = networkTransaction;
	}

	@Override
	public void run() {
		if (submit && ! DbConstant.admin.isConnected) {
			logger.error(Messages.getString("SpooledDirectoryTransfer.2")); //$NON-NLS-1$
			this.future.cancel();
			return;
		}
		sent = 0;
		error  = 0;
		allrhosts = remoteHosts.split(",");
		// first check if rule is for SEND
		DbRule dbrule = null;
		try {
			dbrule = new DbRule(DbConstant.admin.session, rulename);
		} catch (WaarpDatabaseException e1) {
			logger.error(Messages.getString("Transfer.18"), e1); //$NON-NLS-1$
			this.future.setFailure(e1);
			return;
		}
		if (dbrule.isRecvMode()) {
			logger.error(Messages.getString("SpooledDirectoryTransfer.5")); //$NON-NLS-1$
			this.future.cancel();
			return;
		}
		File status = new File(statusFile);
		if (status.isDirectory()) {
			logger.error(Messages.getString("SpooledDirectoryTransfer.6")); //$NON-NLS-1$
			this.future.cancel();
			return;
		}
		File stop = new File(stopFile);
		if (stop.isDirectory()) {
			logger.error(Messages.getString("SpooledDirectoryTransfer.7")); //$NON-NLS-1$
			this.future.cancel();
			return;
		} else if (stop.exists()) {
			logger.warn(Messages.getString("SpooledDirectoryTransfer.8")); //$NON-NLS-1$
			this.future.setSuccess();
			return;
		}
		String [] directories = directory.split(",");
		for (String dirname : directories) {
			File dir = new File(dirname);
			if (!dir.isDirectory()) {
				logger.error(Messages.getString("SpooledDirectoryTransfer.9")+" : "+dir); //$NON-NLS-1$
				this.future.cancel();
				return;
			}
		}
		FileFilter filter = null;
		if (regexFilter != null) {
			filter = new RegexFileFilter(regexFilter);
		}
		// Will be used if no parallelism
		FileMonitorCommandRunnableFuture commandValidFile = new SpooledRunner(null);
		FileMonitorCommandRunnableFuture waarpRemovedCommand = new FileMonitorCommandRunnableFuture() {
			public void run(FileItem file) {
				logger.warn("File removed: {}", file.file);
			}
		};
		FileMonitorCommandRunnableFuture waarpHostCommand = null;
		File dir = new File(directories[0]);
		monitor = new FileMonitor(name, status, stop, dir, null, elapseTime, filter, 
				recurs, commandValidFile, waarpRemovedCommand, null);
		if (parallel) {
			FileMonitorCommandFactory factory = new FileMonitorCommandFactory() {
				
				@Override
				public FileMonitorCommandRunnableFuture create(FileItem fileItem) {
					return new SpooledRunner(fileItem);
				}
			};
			monitor.setCommandValidFileFactory(factory, limitParallelTasks);
		}
		if (waarpHosts != null && ! waarpHosts.isEmpty()) {
			final String [] allwaarps = waarpHosts.split(",");
			waarpHostCommand = new FileMonitorCommandRunnableFuture() {
				public void run(FileItem notused) {
					String status = monitor.getStatus();
					for (String host : allwaarps) {
						host = host.trim();
						if (host != null && ! host.isEmpty()) {
							R66Future future = new R66Future(true);
							BusinessRequestPacket packet =
									new BusinessRequestPacket(SpooledInformTask.class.getName() + " " + status, 0);
							BusinessRequest transaction = new BusinessRequest(
									networkTransaction, future, host, packet);
							transaction.run();
							future.awaitUninterruptibly();
							if (! future.isSuccess()) {
								logger.info("Can't inform Waarp server: "+host + " since " + future.getCause());
							}
						}
					}
				}
			};
			monitor.setCommandCheckIteration(waarpHostCommand);
			monitor.setElapseWaarpTime(elapseWaarpTime);
		}
		for (int i = 1; i < directories.length; i++) {
			dir = new File(directories[i]);
			monitor.addDirectory(dir);
		}
		logger.warn("SpooledDirectoryTransfer starts name:"+name+" directory:"+directory+" statusFile:"+statusFile+" stopFile:"+stopFile+
				" rulename:"+rulename+" fileinfo:"+fileinfo+" hosts:"+remoteHosts+" regex:"+regexFilter+" waarp:"+waarpHosts+
				" elapse:"+elapseTime+" waarpElapse:"+elapseWaarpTime+" parallel:"+parallel+" limitParallel:"+limitParallelTasks+
				" submit:"+submit+" recursive:"+recurs);
		monitor.start();
		monitor.waitForStopFile();
		this.future.setSuccess();
	}
	
	public void stop() {
		if (monitor != null) {
			logger.info("Stop Monitor");
			monitor.stop();
			logger.info("Monitor Stopped");
		} else {
			logger.warn("NO MONITOR found");
		}
	}
	
	public class SpooledRunner extends FileMonitorCommandRunnableFuture {
		public SpooledRunner(FileItem fileItem) {
			super(fileItem);
		}

		public void run(FileItem fileItem) {
			this.fileItem = fileItem;
			boolean finalStatus = false;
			long specialId = allrhosts.length > 1 ? DbConstant.ILLEGALVALUE : fileItem.specialId;
			for (String host : allrhosts) {
				host = host.trim();
				if (host != null && ! host.isEmpty()) {
					String filename = fileItem.file.getAbsolutePath();
					logger.info("Launch transfer to "+host+" with file "+filename);
					R66Future future = new R66Future(true);
					String text = null;
					if (submit) {
						text = "Submit Transfer";
						SubmitTransfer transaction = new SubmitTransfer(future,
								host, filename, rulename, fileinfo, isMD5, blocksize, 
								specialId, null);
						logger.info("SubmitTransfer: "+host);
						transaction.run();
					} else {
						if (specialId != DbConstant.ILLEGALVALUE) {
							text = "Request Transfer Restart";
							try {
								String srequester = Configuration.configuration.getHostId(DbConstant.admin.session,
										host);
								RequestTransfer transaction = new RequestTransfer(future, specialId, host, srequester, 
										false, false, true, networkTransaction);
								logger.info("RequestTransfer: "+host);
								transaction.run();
							} catch (WaarpDatabaseException e) {
								logger.warn(Messages.getString("RequestTransfer.5") + host, e); //$NON-NLS-1$
								text = "Direct Transfer";
								DirectTransfer transaction = new DirectTransfer(future,
										host, filename, rulename, fileinfo, isMD5, blocksize, 
										specialId, networkTransaction);
								logger.info("DirectTransfer2: "+host);
								transaction.run();
							}
						} else {
							text = "Direct Transfer";
							DirectTransfer transaction = new DirectTransfer(future,
									host, filename, rulename, fileinfo, isMD5, blocksize,
									specialId, networkTransaction);
							logger.info("DirectTransfer: "+host);
							transaction.run();
						}
					}
					future.awaitUninterruptibly();
					R66Result r66result = future.getResult();
					if (future.isSuccess()) {
						finalStatus = true;
						sent++;
						DbTaskRunner runner = null;
						if (r66result != null) {
							runner = r66result.runner;
							if (runner != null) {
								specialId = runner.getSpecialId();
								String status = Messages.getString("RequestInformation.Success"); //$NON-NLS-1$
								if (runner.getErrorInfo() == ErrorCode.Warning) {
									status = Messages.getString("RequestInformation.Warned"); //$NON-NLS-1$
								}
								logger.warn(text+" in status: "+status+"     "
										+ runner.toShortString()
										+"     <REMOTE>"+ host+ "</REMOTE>"
										+"     <FILEFINAL>"+
										(r66result.file != null ? 
												r66result.file.toString() + "</FILEFINAL>"
												: "no file"));
								if (nolog && !submit) {
									// In case of success, delete the runner
									try {
										runner.delete();
									} catch (WaarpDatabaseException e) {
										logger.warn("Cannot apply nolog to     " +
												runner.toShortString(),
												e);
									}
								}
							} else {
								logger.warn(text+Messages.getString("RequestInformation.Success")  //$NON-NLS-1$
										+"<REMOTE>" + host + "</REMOTE>");
							}
						} else {
							logger.warn(text+Messages.getString("RequestInformation.Success")  //$NON-NLS-1$
									+"<REMOTE>" + host + "</REMOTE>");
						}
					} else {
						error++;
						DbTaskRunner runner = null;
						if (r66result != null) {
							runner = r66result.runner;
							if (runner != null) {
								specialId = runner.getSpecialId();
								logger.error(text+Messages.getString("RequestInformation.Failure") +  //$NON-NLS-1$
										runner.toShortString() +
									"<REMOTE>" + host + "</REMOTE>", future.getCause());
							} else {
								logger.error(text+Messages.getString("RequestInformation.Failure"),  //$NON-NLS-1$
										future.getCause());
							}
						} else {
							logger.error(text+Messages.getString("RequestInformation.Failure") //$NON-NLS-1$
									+"<REMOTE>" + host + "</REMOTE>", 
									future.getCause());
						}
					}
				}
			}
			specialId = allrhosts.length > 1 ? DbConstant.ILLEGALVALUE : specialId;
			finalize(finalStatus, specialId);
		}
	}

	/**
	 * Default arguments
	 * @author "Frederic Bregier"
	 *
	 */
	protected static class Arguments {
		protected String sname = null;
		protected String rhosts = null;
		protected String localDirectory = null;
		protected String rule = null;
		protected String fileInfo = null;
		protected boolean ismd5 = false;
		protected int block = 0x10000; // 64K as default
		protected String statusfile = null;
		protected String stopfile = null;
		protected String regex = null;
		protected long elapsed = 1000;
		protected long elapsedWaarp = 5000;
		protected boolean tosubmit = true;
		protected boolean noLog = false;
		protected boolean recursive = false;
		protected String waarphosts = null;
		protected boolean isparallel = true;
		protected int limitParallel = 0;
	}
	
	protected static List<Arguments> arguments = new ArrayList<Arguments>();
	private static final String XML_ROOT = "/config/";
	private static final String XML_SPOOLEDDAEMON = "spooleddaemon";
	private static final String XML_stopfile = "stopfile";
	private static final String XML_spooled = "spooled";
	private static final String XML_name = "name";
	private static final String XML_to = "to";
	private static final String XML_rule = "rule";
	private static final String XML_statusfile = "statusfile";
	private static final String XML_directory = "directory";
	private static final String XML_regex = "regex";
	private static final String XML_recursive = "recursive";
	private static final String XML_elapse = "elapse";
	private static final String XML_submit = "submit";
	private static final String XML_parallel = "parallel";
	private static final String XML_limitParallel = "limitParallel";
	private static final String XML_info = "info";
	private static final String XML_md5 = "md5";
	private static final String XML_block = "block";
	private static final String XML_nolog = "nolog";
	private static final String XML_waarp = "waarp";
	private static final String XML_elapseWaarp = "elapseWaarp";
	
	private static final XmlDecl[] subSpooled = {
		new XmlDecl(XmlType.STRING, XML_name),
		new XmlDecl(XML_to, XmlType.STRING, XML_to, true),
		new XmlDecl(XmlType.STRING, XML_rule),
		new XmlDecl(XmlType.STRING, XML_statusfile),
		new XmlDecl(XML_directory, XmlType.STRING, XML_directory, true),
		new XmlDecl(XmlType.STRING, XML_regex),
		new XmlDecl(XmlType.BOOLEAN, XML_recursive),
		new XmlDecl(XmlType.LONG, XML_elapse),
		new XmlDecl(XmlType.BOOLEAN, XML_submit),
		new XmlDecl(XmlType.BOOLEAN, XML_parallel),
		new XmlDecl(XmlType.INTEGER, XML_limitParallel),
		new XmlDecl(XmlType.STRING, XML_info),
		new XmlDecl(XmlType.BOOLEAN, XML_md5),
		new XmlDecl(XmlType.INTEGER, XML_block),
		new XmlDecl(XmlType.BOOLEAN, XML_nolog),
		new XmlDecl(XML_waarp, XmlType.STRING, XML_waarp, true),
		new XmlDecl(XmlType.LONG, XML_elapseWaarp)
	};
	private static final XmlDecl[] spooled = {
		new XmlDecl(XmlType.STRING, XML_stopfile),
		new XmlDecl(XML_spooled, XmlType.XVAL, XML_spooled, subSpooled, true)
	};
	private static final XmlDecl[] configSpooled = {
		new XmlDecl(XML_SPOOLEDDAEMON, XmlType.XVAL, XML_ROOT + XML_SPOOLEDDAEMON, 
				spooled, false)
	};
	@SuppressWarnings("unchecked")
	protected static boolean getParamsFromConfigFile(String filename) {
		Document document = null;
		// Open config file
		try {
			document = new SAXReader().read(filename);
		} catch (DocumentException e) {
			logger.error(Messages.getString("FileBasedConfiguration.CannotReadXml") + filename, e); //$NON-NLS-1$
			return false;
		}
		if (document == null) {
			logger.error(Messages.getString("FileBasedConfiguration.CannotReadXml") + filename); //$NON-NLS-1$
			return false;
		}
		XmlValue [] configuration = XmlUtil.read(document, configSpooled);
		XmlHash hashConfig = new XmlHash(configuration);
		XmlValue value = hashConfig.get(XML_stopfile);
		String stopfile = null;
		if (value == null || (value.isEmpty())) {
			return false;
		}
		stopfile = value.getString();
		value = hashConfig.get(XML_spooled);
		if (value != null && (value.getList() != null)) {
			for (XmlValue[] xml : (List<XmlValue[]>) value.getList()) {
				Arguments arg = new Arguments();
				arg.stopfile = stopfile;
				XmlHash subHash = new XmlHash(xml);
				value = subHash.get(XML_name);
				if (value != null && (!value.isEmpty())) {
					arg.sname = value.getString();
				}
				value = subHash.get(XML_to);
				if (value != null && (value.getList() != null)) {
					arg.rhosts = "";
					for (String to : (List<String>) value.getList()) {
						arg.rhosts += to+",";
					}
					arg.rhosts = arg.rhosts.substring(0, arg.rhosts.length()-1);
				} else {
					continue;
				}
				value = subHash.get(XML_rule);
				if (value != null && (!value.isEmpty())) {
					arg.rule = value.getString();
				} else {
					continue;
				}
				value = subHash.get(XML_statusfile);
				if (value != null && (!value.isEmpty())) {
					arg.statusfile = value.getString();
				} else {
					continue;
				}
				value = subHash.get(XML_directory);
				if (value != null && (value.getList() != null)) {
					arg.localDirectory = "";
					for (String dir : (List<String>) value.getList()) {
						arg.localDirectory += dir+",";
					}
					arg.localDirectory = arg.localDirectory.substring(0, arg.localDirectory.length()-1);
				} else {
					continue;
				}
				value = subHash.get(XML_regex);
				if (value != null && (!value.isEmpty())) {
					arg.regex = value.getString();
				}
				value = subHash.get(XML_recursive);
				if (value != null && (!value.isEmpty())) {
					arg.recursive = value.getBoolean();
				}
				value = subHash.get(XML_elapse);
				if (value != null && (!value.isEmpty())) {
					arg.elapsed = value.getLong();
				}
				value = subHash.get(XML_submit);
				if (value != null && (!value.isEmpty())) {
					arg.tosubmit = value.getBoolean();
				}
				value = subHash.get(XML_parallel);
				if (value != null && (!value.isEmpty())) {
					arg.isparallel = value.getBoolean();
				}
				value = subHash.get(XML_limitParallel);
				if (value != null && (!value.isEmpty())) {
					arg.limitParallel = value.getInteger();
				}
				value = subHash.get(XML_info);
				if (value != null && (!value.isEmpty())) {
					arg.fileInfo = value.getString();
				}
				value = subHash.get(XML_md5);
				if (value != null && (!value.isEmpty())) {
					arg.ismd5 = value.getBoolean();
				}
				value = subHash.get(XML_block);
				if (value != null && (!value.isEmpty())) {
					arg.block = value.getInteger();
				}
				value = subHash.get(XML_nolog);
				if (value != null && (!value.isEmpty())) {
					arg.noLog = value.getBoolean();
				}
				value = subHash.get(XML_waarp);
				if (value != null && (value.getList() != null)) {
					arg.waarphosts = "";
					for (String host : (List<String>) value.getList()) {
						arg.waarphosts += host+",";
					}
					arg.waarphosts = arg.waarphosts.substring(0, arg.waarphosts.length()-1);
				}
				value = subHash.get(XML_elapseWaarp);
				if (value != null && (!value.isEmpty())) {
					arg.elapsedWaarp = value.getLong();
				} else {
					continue;
				}
				arguments.add(arg);
			}
		}
		document = null;
		hashConfig.clear();
		hashConfig = null;
		configuration = null;
		return !arguments.isEmpty();
	}
	/**
	 * Parse the parameter and set current values
	 * 
	 * @param args
	 * @return True if all parameters were found and correct
	 */
	protected static boolean getParams(String[] args) {
		_INFO_ARGS = Messages.getString("SpooledDirectoryTransfer.0"); //$NON-NLS-1$
		if (args.length < 1) {
			logger.error(_INFO_ARGS);
			return false;
		}
		if (!FileBasedConfiguration
				.setClientConfigurationFromXml(Configuration.configuration, args[0])) {
			logger
					.error(Messages.getString("Configuration.NeedCorrectConfig")); //$NON-NLS-1$
			return false;
		}
		// Now check if the configuration file contains already elements of specifications
		if (! getParamsFromConfigFile(args[0])) {
			if (args.length < 11) {
				logger
						.error(_INFO_ARGS);
				return false;
			}
			// Now set default values from configuration
			Arguments arg = new Arguments();
			arg.block = Configuration.configuration.BLOCKSIZE;
			int i = 1;
			try {
				for (i = 1; i < args.length; i++) {
					if (args[i].equalsIgnoreCase("-to")) {
						i++;
						arg.rhosts = args[i];
					} else if (args[i].equalsIgnoreCase("-name")) {
						i++;
						arg.sname = args[i];
					} else if (args[i].equalsIgnoreCase("-directory")) {
						i++;
						arg.localDirectory = args[i];
					} else if (args[i].equalsIgnoreCase("-rule")) {
						i++;
						arg.rule = args[i];
					} else if (args[i].equalsIgnoreCase("-statusfile")) {
						i++;
						arg.statusfile = args[i];
					} else if (args[i].equalsIgnoreCase("-stopfile")) {
						i++;
						arg.stopfile = args[i];
					} else if (args[i].equalsIgnoreCase("-info")) {
						i++;
						arg.fileInfo = args[i];
					} else if (args[i].equalsIgnoreCase("-md5")) {
						arg.ismd5 = true;
					} else if (args[i].equalsIgnoreCase("-block")) {
						i++;
						arg.block = Integer.parseInt(args[i]);
						if (arg.block < 100) {
							logger.error(Messages.getString("AbstractTransfer.1") + arg.block); //$NON-NLS-1$
							return false;
						}
					} else if (args[i].equalsIgnoreCase("-nolog")) {
						arg.noLog = true;
					} else if (args[i].equalsIgnoreCase("-submit")) {
						arg.tosubmit = true;
					} else if (args[i].equalsIgnoreCase("-direct")) {
						arg.tosubmit = false;
					} else if (args[i].equalsIgnoreCase("-recursive")) {
						arg.recursive = true;
					} else if (args[i].equalsIgnoreCase("-regex")) {
						i++;
						arg.regex = args[i];
					} else if (args[i].equalsIgnoreCase("-waarp")) {
						i++;
						arg.waarphosts = args[i];
					} else if (args[i].equalsIgnoreCase("-elapse")) {
						i++;
						arg.elapsed = Long.parseLong(args[i]);
					} else if (args[i].equalsIgnoreCase("-elapseWaarp")) {
						i++;
						arg.elapsedWaarp = Long.parseLong(args[i]);
					} else if (args[i].equalsIgnoreCase("-limitParallel")) {
						i++;
						arg.limitParallel = Integer.parseInt(args[i]);
					} else if (args[i].equalsIgnoreCase("-parallel")) {
						arg.isparallel = true;
					} else if (args[i].equalsIgnoreCase("-sequential")) {
						arg.isparallel = false;
					}
				}
			} catch (NumberFormatException e) {
				logger.error(Messages.getString("AbstractTransfer.20")+i); //$NON-NLS-1$
				return false;
			}
			if (arg.fileInfo == null) {
				arg.fileInfo = NO_INFO_ARGS;
			}
			if (arg.sname == null) {
				arg.sname = Configuration.configuration.HOST_ID+" : "+arg.localDirectory;
			}
			if (arg.tosubmit && ! DbConstant.admin.isConnected) {
				logger.error(Messages.getString("SpooledDirectoryTransfer.2")); //$NON-NLS-1$
				return false;
			}
			if (arg.rhosts != null && arg.rule != null && 
					arg.localDirectory != null && 
							arg.statusfile != null && arg.stopfile != null) {
				arguments.add(arg);
				return true;
			}
			logger.error(Messages.getString("SpooledDirectoryTransfer.56")+ //$NON-NLS-1$
					_INFO_ARGS);
			return false;
		}
		return ! arguments.isEmpty();
	}

	public static void main(String[] args) {
		InternalLoggerFactory.setDefaultFactory(new WaarpSlf4JLoggerFactory(null));
		if (logger == null) {
			logger = WaarpInternalLoggerFactory.getLogger(SpooledDirectoryTransfer.class);
		}
		initialize(args, true);
	}

	public static List<SpooledDirectoryTransfer> list = new ArrayList<SpooledDirectoryTransfer>();
	public static NetworkTransaction networkTransactionStatic = null;
	public static ExecutorService executorService = null;
	/**
	 * @param args
	 * @param normalStart if True, will exit JVM when all daemons are stopped; else False let the caller do (used by SpooledEngine)
	 */
	public static boolean initialize(String[] args, boolean normalStart) {
		if (logger == null) {
			logger = WaarpInternalLoggerFactory.getLogger(SpooledDirectoryTransfer.class);
		}
		if (!getParams(args)) {
			logger.error(Messages.getString("Configuration.WrongInit")); //$NON-NLS-1$
			if (DbConstant.admin != null && DbConstant.admin.isConnected) {
				DbConstant.admin.close();
			}
			if (normalStart) {
				ChannelUtils.stopLogger();
				System.exit(2);
			}
			return false;
		}

		Configuration.configuration.pipelineInit();
		networkTransactionStatic = new NetworkTransaction();
		try {
			executorService = Executors.newCachedThreadPool(new WaarpThreadFactory("SpooledDirectoryDaemon"));
			for (Arguments arg : arguments) {
				R66Future future = new R66Future(true);
				SpooledDirectoryTransfer spooled =
						new SpooledDirectoryTransfer(future, arg.sname, arg.localDirectory, arg.statusfile, arg.stopfile,
								arg.rule, arg.fileInfo, arg.ismd5, arg.rhosts, arg.block, arg.regex, arg.elapsed, 
								arg.tosubmit, arg.noLog, arg.recursive,
								arg.elapsedWaarp, arg.isparallel, arg.limitParallel, arg.waarphosts, networkTransactionStatic);
				executorService.submit(spooled);
				list.add(spooled);
			}
			arguments.clear();
			Thread.sleep(1000);
			executorService.shutdown();
			if (normalStart) {
				while (! executorService.awaitTermination(Configuration.configuration.TIMEOUTCON, TimeUnit.MILLISECONDS)) {
					Thread.sleep(Configuration.configuration.TIMEOUTCON);
				}
				for (SpooledDirectoryTransfer spooledDirectoryTransfer : list) {
					logger.warn(Messages.getString("SpooledDirectoryTransfer.58")+spooledDirectoryTransfer.name+": "+spooledDirectoryTransfer.sent
							+" success, "+spooledDirectoryTransfer.error
							+Messages.getString("SpooledDirectoryTransfer.60")); //$NON-NLS-1$
				}
				list.clear();
			}
			return true;
		} catch (Exception e) {
			logger.warn("exc", e);
			return false;
		} finally {
			if (normalStart) {
				networkTransactionStatic.closeAll();
				System.exit(0);
			}
		}
	}
	
}