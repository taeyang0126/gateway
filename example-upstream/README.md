# example-upstream

用于网关开发和测试的 Mock 上游服务。

## 定位

提供一个简单的 Spring Boot Web 应用作为上游后端，方便本地调试网关的路由转发功能。

## 启动

```bash
mvn spring-boot:run -pl example-upstream
```

## 接口验证

> 以下命令均通过网关（端口 8080）访问，请确保网关已启动。

```bash
# 普通接口（无需认证）
curl http://localhost:8080/api/example/hello

# POST echo
curl -X POST http://localhost:8080/api/example/echo \
  -H "Content-Type: text/plain" -d "hello"

# 单文件上传
curl -X POST http://localhost:8080/api/example/upload \
  -F "file=@/path/to/your-file"

# 多文件上传
curl -X POST http://localhost:8080/api/example/upload/multi \
  -F "files=@/path/to/file1" \
  -F "files=@/path/to/file2"

# 文件 + 表单字段
curl -X POST http://localhost:8080/api/example/upload/with-fields \
  -F "file=@/path/to/your-file" \
  -F "name=test" -F "description=demo"

# 大文件下载（10MB）
curl http://localhost:8080/api/example/download -o testfile.bin

# 受保护接口（需要 JWT）
curl -H "Authorization: Bearer <token>" \
  http://localhost:8080/api/example/private/profile
```
