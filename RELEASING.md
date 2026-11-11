# 发布到 Maven Central

本项目通过 Sonatype Central Portal 发布,坐标为(多模块):

```text
io.github.ispengya:sharding-parent        # 聚合 POM
io.github.ispengya:sharding-core          # 核心(Java 8 基线)
io.github.ispengya:sharding-boot2-starter # Spring Boot 2.x 装配线
io.github.ispengya:sharding-boot3-starter # Spring Boot 3.x 装配线
```

发布方式与 `netty-remoting-framework` / `mini-file-storage` 一致。

## 1. 前置条件

- 构建环境:**JDK 17** + Maven(boot3-starter 需要 17;core / boot2-starter 由 javac17
  以 8 字节码基线编译,产物仍兼容 Java 8 运行时)
- 发布前可选:用 JDK 8 跑一遍兼容性验证(`JAVA_HOME=<jdk8> mvn test -pl sharding-core,sharding-boot2-starter`,
  或直接 `bash scripts/test.sh` 跑双 JDK 矩阵)
- Central Portal 账号:<https://central.sonatype.com>
- 已验证的 namespace:`io.github.ispengya`
- GPG 密钥(有私钥,公钥已上传到 keyserver)
- Central Portal user token(不是登录密码)

## 2. 配置 `~/.m2/settings.xml`

在 `<servers>` 中加入:

```xml
<server>
  <id>central</id>
  <username>TOKEN_USERNAME</username>
  <password>TOKEN_PASSWORD</password>
</server>
```

`id` 必须和 POM 里的 `publishingServerId` 一致(`central`)。

## 3. GPG

发布用的 GPG 密钥和 passphrase 由维护者在本机管理。**签名参数不写入 POM**,
release profile 只引用 `${gpg.keyname}` / `${gpg.passphrase.file}`,
由发布命令经 `-D` 注入;口令文件放仓库外。不要把私钥、passphrase 或 Central token 提交到仓库。

- `gpg.keyname`:本机私钥指纹,`gpg --list-secret-keys --keyid-format LONG` 查看
- `gpg.passphrase.file`:口令文件路径(本机为 `~/.m2/central-gpg-passphrase`)

漏传 `-D` 会在 verify 阶段签名时失败——发生在上传之前,不会产生半成品 deployment。

## 4. 本地验证构建

```bash
mvn clean test -pl sharding-core,sharding-boot2-starter -Dtest='!*IntegrationTest'   # 无 Docker 时跳过集成测试
mvn -Prelease -Dgpg.skip=true -DskipTests clean package
```

第二条命令会为全部四个 artifact 生成 sources/javadoc jar(boot2 线 javadoc 为 release 8,
boot3 线为 17),用于提前发现打包问题,但不签名。

## 5. 发布

```bash
mvn clean deploy -Prelease \
  -Dgpg.keyname=E0D0D22DBB41E745173E30AC356D8913E73CC2F8 \
  -Dgpg.passphrase.file="$HOME/.m2/central-gpg-passphrase"
```

一次 deploy 发布整个 reactor(parent + core + 两个 starter)。

首次发布使用 `autoPublish=false`:

1. 命令上传 bundle 并等待 Central Portal 校验(`waitUntil=validated`);
2. 打开 <https://central.sonatype.com/publishing>;
3. 确认 deployment 校验通过后点击 Publish。

发布成功后,依赖坐标(使用方按 Boot 版本选择):

```xml
<!-- Spring Boot 2.x 应用 -->
<dependency>
  <groupId>io.github.ispengya</groupId>
  <artifactId>sharding-boot2-starter</artifactId>
  <version>0.2.0</version>
</dependency>

<!-- Spring Boot 3.x 应用 -->
<dependency>
  <groupId>io.github.ispengya</groupId>
  <artifactId>sharding-boot3-starter</artifactId>
  <version>0.2.0</version>
</dependency>
```

`repo1.maven.org` 同步可能需要几分钟,可以用下面的命令验证:

```bash
mvn dependency:get -Dartifact=io.github.ispengya:sharding-boot2-starter:0.2.0
```

## 6. 后续版本

- 修改根 POM 的 `<version>`(全模块统一版本,或用 `mvn versions:set -DnewVersion=x.y.z`);
- 提交并打 tag:

```bash
git tag vX.Y.Z
git push origin master --tags
```

- 再执行第 5 节的发布命令(带两个 `-D` GPG 参数)。

## 注意

- 版本号和 groupId 一旦发布不可修改。
- Central 要求主 jar、sources jar、javadoc jar 都有 `.asc` 签名(四个 artifact 全部)。
- 发布前确保 `LICENSE`、POM 的 `licenses/developers/scm/url` 与实际仓库一致。
- 集成测试需 Docker(`bash scripts/test.sh` 双 JDK 矩阵);发布前至少跑通单测。
