-- 复制账号（mysql-slave 使用）；仅 master-1 需要，master-2 上创建无害
GRANT REPLICATION SLAVE ON *.* TO 'repl'@'%' IDENTIFIED BY 'repl';
FLUSH PRIVILEGES;
