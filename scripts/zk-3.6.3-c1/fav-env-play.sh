export MSG_OPT=false
#export PHOS_OPTS="-Xbootclasspath/a:/home/gaoyu/Phosphor-0.0.5-SNAPSHOT.jar -javaagent:/home/gaoyu/Phosphor-0.0.5-SNAPSHOT.jar=useFav=false,hbaseRpc=true,cacheDir=/home/gaoyu/evaluation/hbase-1.7.1/favcache"
export PHOS_OPTS="-Xbootclasspath/a:/home/gaoyu/Phosphor-0.0.5-SNAPSHOT.jar -javaagent:/home/gaoyu/Phosphor-0.0.5-SNAPSHOT.jar=useFav=false,useMsgid=false,jdkMsg=false"
#export PHOS_OPTS="-Xbootclasspath/a:/home/gaoyu/Phosphor-0.0.5-SNAPSHOT.jar -javaagent:/home/gaoyu/Phosphor-0.0.5-SNAPSHOT.jar=useFav=false"
#export FAV_OPTS="$PHOS_OPTS"

export FAV_OPTS="-Xbootclasspath/a:/home/gaoyu/Phosphor-0.0.5-SNAPSHOT.jar -javaagent:/home/gaoyu/Phosphor-0.0.5-SNAPSHOT.jar=useFav=true,useMsgid=false,jdkMsg=false,jdkFile=true,recordPhase=false,recordPath=/home/gaoyu/zk363-fav-rst/,dataPaths=/home/gaoyu/evaluation/zk-3.6.3/zkData/version-2,cacheDir=/home/gaoyu/CacheFolder,favHome=/home/gaoyu,hdfsApi=false,forZk=true,forHbase=false,isThirdPartyProto=false,currentCrash=$FAV_HOME/zk363curCrash,controllerSocket=124.16.138.61:13900,strictCheck=false,mapSize=10000,wordSize=64,covPath=/home/gaoyu/fuzzcov,covIncludes=org/apache/zookeeper,aflAllow=/home/gaoyu/evaluation/zk-3.6.3/allowlist,aflDeny=/home/gaoyu/evaluation/zk-3.6.3/denylist,aflPort=12181"
export TIME_OPTS="-Dfile.encoding=UTF8 -Duser.timezone=GMT+08"
