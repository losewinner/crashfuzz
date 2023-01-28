package edu.iscas.CCrashFuzzer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.Arrays;

import org.apache.commons.io.FileUtils;

import edu.iscas.CCrashFuzzer.utils.FileUtil;

public class CloudFuzzMain {

	public static void main(String[] args) throws IOException {
		// TODO Auto-generated method stub
		if(args.length < 2) {
			System.out.println("Please specify the controller port and configuration file!");
			return;
		}
		
		File confFile = new File(args[1]);
		if(!confFile.exists()) {
			System.out.println("The configuration file does not exist!");
			return;
		}

		Conf conf = new Conf(confFile);
		conf.CONTROLLER_PORT = Integer.parseInt(args[0].trim());
		conf.loadConfiguration();
		
		RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        //System.out.println(runtimeMXBean.getName());
        int myproc = Integer.valueOf(runtimeMXBean.getName().split("@")[0]).intValue();
		FileUtils.writeByteArrayToFile(new File(FileUtil.root+FileUtil.fuzzer_id_file), String.valueOf(myproc).getBytes());

		boolean allowRecovery = false;
		if(args.length >= 3 && args[2].equals("-recover")) {
			allowRecovery = true;
		}
		
		Fuzzer fuzzer = new Fuzzer(new FuzzTarget(), conf, allowRecovery);
		fuzzer.start();
	}
}
