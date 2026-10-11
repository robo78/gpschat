#!/usr/bin/env python3
"""Reproducible Android cloud toolchain; no changes to source or lockfiles."""
import hashlib
import os
from pathlib import Path
import shutil
import ssl
import subprocess
import tarfile
import urllib.parse
import urllib.request
import zipfile

ROOT = Path('/workspace/tooling')
SDK = ROOT / 'android-sdk'
DOWNLOADS = ROOT / 'downloads'
GRADLE = ROOT / 'gradle-8.9'
PROJECT = Path(__file__).resolve().parents[1]
SDK_SHA1 = 'd313adb7aedccf6cf0cfca51ec180f0059f5f8f8'
JDK = ROOT / 'jdk-17.0.20.1+1'
JDK_SHA256 = '3808d1d15e3ec6bd5b84057fb5d84c33d8a1536a258146bcea2e603fc726e08e'


def fetch(url, target):
    context = ssl.create_default_context(cafile=os.environ.get('SSL_CERT_FILE') or None)
    with urllib.request.urlopen(url, context=context, timeout=90) as response, target.open('wb') as out:
        shutil.copyfileobj(response, out)


def verified_zip(url, target, algorithm, checksum, destination):
    if not target.exists() or hashlib.new(algorithm, target.read_bytes()).hexdigest() != checksum:
        fetch(url, target)
    if hashlib.new(algorithm, target.read_bytes()).hexdigest() != checksum:
        raise RuntimeError('Official archive checksum mismatch: ' + target.name)
    with zipfile.ZipFile(target) as archive:
        archive.extractall(destination)


def main():
    for directory in (ROOT, SDK, DOWNLOADS, ROOT / 'android-user', ROOT / 'gradle-user', ROOT / 'user-home/.android'):
        directory.mkdir(parents=True, exist_ok=True)
    if not (JDK / 'bin/jlink').is_file():
        archive = DOWNLOADS / 'jdk.tar.gz'
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != JDK_SHA256:
            fetch('https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/OpenJDK17U-jdk_x64_linux_hotspot_17.0.20.1_1.tar.gz', archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != JDK_SHA256:
            raise RuntimeError('Official JDK archive checksum mismatch.')
        with tarfile.open(archive) as archive:
            archive.extractall(ROOT, filter='data')
    if not (GRADLE / 'bin/gradle').exists():
        checksum = next(line.split('=', 1)[1].strip() for line in
                        (PROJECT / 'gradle/wrapper/gradle-wrapper.properties').read_text().splitlines()
                        if line.startswith('distributionSha256Sum='))
        verified_zip('https://services.gradle.org/distributions/gradle-8.9-bin.zip',
                     DOWNLOADS / 'gradle.zip', 'sha256', checksum, ROOT)
        (GRADLE / 'bin/gradle').chmod(0o755)
    if not (SDK / 'cmdline-tools/latest/bin/sdkmanager').exists():
        verified_zip('https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip',
                     DOWNLOADS / 'sdk.zip', 'sha1', SDK_SHA1, SDK)
        tools = SDK / 'cmdline-tools'
        (tools / 'latest').mkdir(exist_ok=True)
        for name in ('bin', 'lib', 'NOTICE.txt', 'source.properties'):
            shutil.move(str(tools / name), str(tools / 'latest' / name))
        for executable in (tools / 'latest/bin').iterdir():
            executable.chmod(0o755)

    # Java does not inherit curl's proxy/trust settings. Preserve normal roots and
    # add the platform-provided CA, rather than bypassing TLS verification.
    java = JDK / 'bin/java'
    cacerts = java.parents[1] / 'lib/security/cacerts'
    trust = ROOT / 'java-cacerts'
    shutil.copyfile(cacerts, trust)
    cert = os.environ.get('CODEX_PROXY_CERT')
    if cert and Path(cert).is_file():
        subprocess.run(['keytool', '-importcert', '-noprompt', '-alias', 'cloud-proxy',
                        '-file', cert, '-keystore', str(trust), '-storepass', 'changeit'], check=True)
    props = [f'systemProp.javax.net.ssl.trustStore={trust}',
             'systemProp.javax.net.ssl.trustStorePassword=changeit',
             f'systemProp.user.home={ROOT}/user-home']
    proxy = urllib.parse.urlparse(os.environ.get('HTTPS_PROXY', ''))
    if proxy.hostname:
        if proxy.username or proxy.password:
            raise RuntimeError('Authenticated proxy requires a supported external Java binding.')
        for protocol in ('http', 'https'):
            props.extend([f'systemProp.{protocol}.proxyHost={proxy.hostname}',
                          f'systemProp.{protocol}.proxyPort={proxy.port or 80}'])
        props.append('systemProp.http.nonProxyHosts=localhost|127.*|[::1]')
    # Dedicated generated tooling configuration, never a user configuration.
    (ROOT / 'gradle-user/gradle.properties').write_text('\n'.join(props) + '\n')
    env = os.environ | {'ANDROID_USER_HOME': str(ROOT / 'android-user'), 'JAVA_HOME': str(JDK)}
    env['JAVA_OPTS'] = env.get('JAVA_OPTS', '') + f' -Djavax.net.ssl.trustStore={trust} -Djavax.net.ssl.trustStorePassword=changeit'
    manager = str(SDK / 'cmdline-tools/latest/bin/sdkmanager')
    packages = {'platform-tools': 'platform-tools/adb', 'platforms;android-35': 'platforms/android-35/android.jar',
                'build-tools;34.0.0': 'build-tools/34.0.0/aapt2', 'build-tools;35.0.0': 'build-tools/35.0.0/aapt2'}
    missing = [name for name, filename in packages.items() if not (SDK / filename).is_file()]
    if missing:
        arguments = [manager, '--sdk_root=' + str(SDK)]
        if proxy.hostname:
            arguments.extend(['--proxy=http', '--proxy_host=' + proxy.hostname, '--proxy_port=' + str(proxy.port or 80)])
        licenses = subprocess.run(arguments + ['--licenses'], input='y\n' * 100, text=True, env=env, capture_output=True)
        if licenses.returncode:
            raise RuntimeError('Could not accept Android SDK licenses; check SDK networking.')
        subprocess.run(arguments + missing, env=env, check=True)
    for package in ('platform-tools/adb', 'platforms/android-35/android.jar', 'build-tools/34.0.0/aapt2'):
        if not (SDK / package).is_file():
            raise RuntimeError('SDK package not installed: ' + package)
    print('Verified Android toolchain available under /workspace/tooling.')


if __name__ == '__main__':
    main()
