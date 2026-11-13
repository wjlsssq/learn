-- 固定窗口限流：计数与设置 TTL 必须在一个脚本内，防止 INCR 后进程崩溃留下永不过期的键。
-- 这里只执行常数次命令，没有遍历、网络或等待；脚本原子执行不代表错误时自动回滚。
local count = redis.call('INCR', KEYS[1])
if count == 1 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return count
