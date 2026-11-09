# 发布到 Maven Central

本项目通过 Sonatype Central Portal 发布,坐标为:

```text
io.github.ispengya:sharding
```

发布方式与 `netty-remoting-framework` / `mini-file-storage` 一致。

## 1. 前置条件

- 构建环境:**JDK 8** + Maven(Boot 2.1.3 管理的 Lombok 与 JDK 17 不兼容,与 `scripts/test.sh` 同款切换)
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
mvn clean test -Dtest='!*IntegrationTest'   # 无 Docker 时跳过集成测试
mvn -Prelease -Dgpg.skip=true -DskipTests clean package
```

第二条命令会额外生成 sources/javadoc jar,用于提前发现打包问题,但不签名。

## 5. 发布

```bash
mvn clean deploy -Prelease \
  -Dgpg.keyname=E0D0D22DBB41E745173E30AC356D8913E73CC2F8 \
  -Dgpg.passphrase.file="$HOME/.m2/central-gpg-passphrase"
```

首次发布使用 `autoPublish=false`:

1. 命令上传 bundle 并等待 Central Portal 校验(`waitUntil=validated`);
2. 打开 <https://central.sonatype.com/publishing>;
3. 确认 deployment 校验通过后点击 Publish。

发布成功后,依赖坐标:

```xml
<dependency>
  <groupId>io.github.ispengya</groupId>
  <artifactId>sharding</artifactId>
  <version>0.1.0</version>
</dependency>
```

`repo1.maven.org` 同步可能需要几分钟,可以用下面的命令验证:

```bash
mvn dependency:get -Dartifact=io.github.ispengya:sharding:0.1.0
```

## 6. 后续版本

- 修改 `pom.xml` 的 `<version>`(或用 `mvn versions:set -DnewVersion=x.y.z`);
- 提交并打 tag:

```bash
git tag vX.Y.Z
git push origin master --tags
```

- 再执行第 5 节的发布命令(带两个 `-D` GPG 参数)。

## 注意

- 版本号和 groupId 一旦发布不可修改。
- Central 要求主 jar、sources jar、javadoc jar 都有 `.asc` 签名。
- 发布前确保 `LICENSE`、POM 的 `licenses/developers/scm/url` 与实际仓库一致。
- 集成测试需 Docker(`bash scripts/test.sh`);发布前至少跑通单测。
