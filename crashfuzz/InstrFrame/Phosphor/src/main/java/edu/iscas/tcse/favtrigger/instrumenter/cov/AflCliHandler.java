package edu.iscas.tcse.favtrigger.instrumenter.cov;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

import edu.iscas.tcse.favtrigger.instrumenter.cov.AflCli.AflCommand;

public class AflCliHandler extends Thread {
    final Socket serverClient;
	final int clientNo;
	public AflCliHandler(Socket socket,  int id) {
		this.serverClient = socket;
		this.clientNo = id;
	}

	public void run() {
	    try{
	      DataInputStream inStream = new DataInputStream(serverClient.getInputStream());
	      DataOutputStream outStream = new DataOutputStream(serverClient.getOutputStream());
	      String clientMessage = "", serverMessage = "";
	      clientMessage = inStream.readUTF();
	      System.out.println("From AFL Client-" +clientNo+ ": msg is :"+clientMessage);

	      serverMessage = AflCommand.FINISH.toString();
	      if(clientMessage.equals(AflCommand.SAVE.toString())) {
	    	  try{
		    	  JavaAfl.save_result();
		      } catch (Exception e) {
		    	  serverMessage = e.getMessage();
		    	  e.printStackTrace();
		      }
	      } else if (clientMessage.equals(AflCommand.STABLE.toString())) {
	    	  try{
		    	  boolean rst = JavaAfl.waitStable();
		    	  if(!rst) {
		    		  serverMessage = AflCommand.TMOUT.toString();
		    	  }
		      } catch (Exception e) {
		    	  serverMessage = e.getMessage();
		    	  e.printStackTrace();
		      }
	      } else {
	    	  serverMessage = "Illegal command: "+clientMessage;
	      }

	      //notify client
	      System.out.println("To AFL Client -" + clientNo + ":"+serverMessage);
	      outStream.writeUTF(serverMessage);
	      outStream.flush();
	      inStream.close();
	      outStream.close();
	      serverClient.close();
	    } catch(Exception e) {
	    	e.printStackTrace();
	    } finally {
	      System.out.println("AFL Client -" + clientNo + " exit!! ");
	    }
	  }
}
