# Releasing ZIO OpenFeature

This project uses [sbt-ci-release](https://github.com/sbt/sbt-ci-release) for automated releases to Maven Central.

## Version Scheme

Versions follow [Semantic Versioning](https://semver.org/):
- **MAJOR** version for incompatible API changes
- **MINOR** version for backwards-compatible functionality additions
- **PATCH** version for backwards-compatible bug fixes

Versions are derived automatically from git tags using [sbt-dynver](https://github.com/sbt/sbt-dynver):
- Tagged commits: `v0.1.0` → version `0.1.0`
- Untagged commits: `0.1.0+3-abcd1234-SNAPSHOT` (3 commits after v0.1.0)

## Setup (One-time)

### 1. Create Sonatype Account

1. Create an account at https://issues.sonatype.org
2. Create a New Project ticket requesting access to `io.github.etacassiopeia`
3. Wait for approval (usually within 2 business days)

### 2. Generate PGP Key

```bash
# Generate a new PGP key
gpg --gen-key

# List keys to get the key ID
gpg --list-keys

# Export the secret key (base64 encoded)
gpg --export-secret-keys --armor YOUR_KEY_ID | base64

# Get the passphrase you used when creating the key
```

### 3. Configure GitHub Secrets

Go to your repository's Settings → Secrets and variables → Actions, and add:

| Secret | Description |
|--------|-------------|
| `SONATYPE_USERNAME` | Your Sonatype JIRA username |
| `SONATYPE_PASSWORD` | Your Sonatype JIRA password |
| `PGP_SECRET` | Base64-encoded PGP secret key |
| `PGP_PASSPHRASE` | Passphrase for the PGP key |

### 4. Upload PGP Key to Keyserver

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
```

## Releasing

### Automatic Releases

Create and push a tag to trigger a release:

```bash
# Create a tag following SemVer
git tag v0.1.0

# Push the tag
git push origin v0.1.0
```

The CI will:
1. Run all tests
2. Publish to Maven Central
3. Create a GitHub Release with changelog

### Manual Release (if needed)

```bash
# Set environment variables
export PGP_PASSPHRASE="your-passphrase"
export PGP_SECRET="$(gpg --export-secret-keys --armor YOUR_KEY_ID | base64)"
export SONATYPE_USERNAME="your-username"
export SONATYPE_PASSWORD="your-password"

# Publish
sbt ci-release
```

## Verifying Release

After a release, artifacts will be available:

1. **Immediately** on Sonatype staging: https://s01.oss.sonatype.org
2. **Within ~30 minutes** on Maven Central: https://repo1.maven.org/maven2/io/github/etacassiopeia/

### Check Maven Central

```bash
# Check if published
curl -s "https://repo1.maven.org/maven2/io/github/etacassiopeia/zio-openfeature-core_3/maven-metadata.xml"
```

## Troubleshooting

### Release Failed

1. Check GitHub Actions logs for errors
2. Verify all secrets are correctly configured
3. Ensure PGP key is uploaded to keyserver

### Artifact Not Found

- Maven Central sync can take up to 2 hours
- Check Sonatype staging repository for issues

### Signature Verification Failed

- Ensure PGP key is uploaded to a public keyserver
- Verify the key hasn't expired
