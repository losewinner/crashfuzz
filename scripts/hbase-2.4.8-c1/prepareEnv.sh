sh restartAllDockers.sh
docker exec -t C1HM1 /bin/bash -ic '/etc/init.d/ssh start'
docker exec -t C1HM2 /bin/bash -ic '/etc/init.d/ssh start'
docker exec -t C1RS1 /bin/bash -ic '/etc/init.d/ssh start'
docker exec -t C1RS2 /bin/bash -ic '/etc/init.d/ssh start'
docker exec -t C1RS3 /bin/bash -ic '/etc/init.d/ssh start'
sh fixHosts.sh

#sh stopHDFS.sh
docker restart C1hb-hdfs
docker exec -t C1hb-hdfs /bin/bash -ic "jps && pkill -9 -u root && jps"
docker exec -t C1hb-hdfs /bin/bash -ic 'cd /home/gaoyu/evaluation/hb-hadoop-3.2.2/ && rm -rf logs/*'
sh startHDFS.sh
docker exec -t C1hb-zk /bin/bash -ic 'cd /home/gaoyu/evaluation/hb-zk-3.6.3/ && bin/zkCli.sh -server localhost:11181 deleteall /hbase'

#sh clearRst.sh
sh clearDockerRst.sh
sh clearHB.sh

