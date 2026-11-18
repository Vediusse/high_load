#!/bin/bash
set -euo pipefail
bootstrap=kafka-1:9092,kafka-2:9092,kafka-3:9092
for topic in order-events production-events file-events; do
  for name in "$topic" "$topic.DLT"; do
    /opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --create --if-not-exists \
      --topic "$name" --partitions 3 --replication-factor 3 --config min.insync.replicas=2
  done
done
/opt/kafka/bin/kafka-topics.sh --bootstrap-server "$bootstrap" --describe
