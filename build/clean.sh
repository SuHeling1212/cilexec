#!/bin/bash
echo "cleanup"
mvn clean
cd /Users/administrator/project/Cilexec
rm -r /Users/administrator/project/Cilexec/cilexec_root
rm cilexec.log
echo "cleanup finished"