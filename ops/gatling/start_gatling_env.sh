#!/bin/sh
echo "正在为Gatling压测优化网络参数..."
sudo sysctl -w net.inet.ip.portrange.first=10240
sudo sysctl -w net.inet.tcp.msl=5000
echo "网络参数优化完成。"


