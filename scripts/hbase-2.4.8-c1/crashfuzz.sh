java -cp CCrashFuzzer-0.0.1-SNAPSHOT.jar edu.iscas.CCrashFuzzer.CloudFuzzMain 14900 "hb.properties"
#sh snapshotState.sh
sh restartAllDockers.sh
#java -cp Phosphor-0.0.5-SNAPSHOT.jar edu.iscas.tcse.favtrigger.crash.controller.docker.CrashTriggerSomeMain 11900 "hb.properties" 20:21:22:23:24:25 10
#java -cp Phosphor-0.0.5-SNAPSHOT.jar edu.iscas.tcse.favtrigger.crash.controller.docker.fortest.InjectOneCrashMain 11900 "hb.properties"
