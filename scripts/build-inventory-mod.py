#!/usr/bin/env python3
"""Build and test the standalone Fabric 26.3 mod using the installed game libraries."""
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import time
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APPDATA = Path(os.environ['APPDATA']) if os.name == 'nt' else Path.home() / 'Library/Application Support'
GAME = APPDATA / 'Antagon Client/minecraft-26.3'
BUILD = ROOT / 'build/inventory-mod'
ARTIFACT = ROOT / 'assets/antagon-inventory-26.3.jar'


def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()


def metadata(file):
    with zipfile.ZipFile(file) as archive:
        return json.loads(archive.read('fabric.mod.json'))


def java_home():
    configured = os.environ.get('ANTAGON_INVENTORY_JAVA_HOME') or os.environ.get('JAVA_HOME')
    if configured:
        return Path(configured)
    if sys.platform == 'darwin':
        return Path(subprocess.check_output(['/usr/libexec/java_home', '-v', '25+'], text=True).strip())
    executable = shutil.which('javac')
    if not executable:
        raise RuntimeError('JDK 25 ou mais recente não encontrado.')
    return Path(executable).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install', action='store_true', help='Instalar no perfil 26.3 e arquivar o IPN anterior.')
    args = parser.parse_args()
    installation = json.loads((GAME / 'installation.json').read_text())
    if installation.get('version') != '26.3':
        raise RuntimeError('O perfil instalado precisa ser Minecraft 26.3.')
    classpath = [Path(file) for file in installation['classpath']]
    if not all(file.is_file() for file in classpath):
        raise RuntimeError('Bibliotecas do jogo ausentes; abra o Antagon Client para preparar o perfil.')
    apis = [file for file in (GAME / 'mods').glob('*.jar') if metadata(file)['id'] == 'fabric-api']
    if len(apis) != 1:
        raise RuntimeError('Esperada uma instalação do Fabric API no perfil 26.3.')
    BUILD.mkdir(parents=True, exist_ok=True)
    api_dir = BUILD / 'api'
    if api_dir.exists():
        shutil.rmtree(api_dir)
    api_dir.mkdir()
    with zipfile.ZipFile(apis[0]) as archive:
        for file in archive.namelist():
            if file.endswith('.jar'):
                target = api_dir / Path(file).name
                target.write_bytes(archive.read(file))
                classpath.append(target)
    cp = os.pathsep.join(map(str, classpath))
    (BUILD / 'classpath.txt').write_text(cp)
    home = java_home()
    suffix = '.exe' if os.name == 'nt' else ''
    javac, java = home / ('bin/javac' + suffix), home / ('bin/java' + suffix)
    classes = BUILD / 'classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir()
    sources = sorted((ROOT / 'inventory-mod-src').rglob('*.java'))
    subprocess.run([str(javac), '--release', '25', '-proc:none', '-encoding', 'UTF-8', '-cp', cp,
                    '-d', str(classes), *map(str, sources)], check=True)
    test_classes = BUILD / 'core-test'
    test_classes.mkdir(exist_ok=True)
    subprocess.run([str(javac), '--release', '25', '-encoding', 'UTF-8', '-d', str(test_classes),
                    str(ROOT / 'inventory-mod-src/studio/antagon/inventory/ClickPlan.java'),
                    str(ROOT / 'inventory-mod-src/studio/antagon/inventory/DragGesture.java'),
                    str(ROOT / 'inventory-mod-src/studio/antagon/inventory/InventoryConfig.java'),
                    str(ROOT / 'tests/java/studio/antagon/inventory/ClickPlanTest.java'),
                    str(ROOT / 'tests/java/studio/antagon/inventory/DragGestureTest.java'),
                    str(ROOT / 'tests/java/studio/antagon/inventory/InventoryConfigTest.java')], check=True)
    test = subprocess.run([str(java), '-Xmx512m', '-cp', str(test_classes),
                           'studio.antagon.inventory.ClickPlanTest'], check=True, text=True, capture_output=True)
    print(test.stdout.strip())
    drag_test = subprocess.run([str(java), '-cp', str(test_classes),
                           'studio.antagon.inventory.DragGestureTest'], check=True, text=True, capture_output=True)
    print(drag_test.stdout.strip())
    config_test = subprocess.run([str(java), '-cp', str(test_classes),
                           'studio.antagon.inventory.InventoryConfigTest'], check=True, text=True, capture_output=True)
    print(config_test.stdout.strip())
    integration_cp = os.pathsep.join([str(classes), cp])
    subprocess.run([str(javac), '--release', '25', '-proc:none', '-encoding', 'UTF-8', '-cp', integration_cp,
                    '-d', str(test_classes), str(ROOT / 'tests/java/studio/antagon/inventory/StorageTransferTest.java')], check=True)
    storage_test = subprocess.run([str(java), '-Xmx768m', '-cp', os.pathsep.join([str(test_classes), integration_cp]),
                           'studio.antagon.inventory.StorageTransferTest'], text=True, capture_output=True)
    (BUILD / 'storage-test.log').write_text(storage_test.stdout + storage_test.stderr)
    if storage_test.returncode:
        print((storage_test.stdout + storage_test.stderr)[-6000:])
        raise RuntimeError('Falha no teste de transferência com inventários reais do Minecraft.')
    print('\n'.join(line for line in storage_test.stdout.splitlines() if 'OK:' in line))
    # Verify the only remaining mixin against this exact Minecraft build.
    reference = subprocess.check_output([str(home / ('bin/javap' + suffix)), '-p', '-classpath', cp,
        'net.minecraft.client.gui.screens.inventory.AbstractContainerScreen'], text=True)
    for member in ('leftPos;', 'topPos;', 'imageWidth;', 'imageHeight;'):
        if member not in reference:
            raise RuntimeError('Alvo de mixin ausente: ' + member)
    resources = ROOT / 'inventory-mod-resources'
    for file in resources.rglob('*.json'):
        json.loads(file.read_text())
    locales = [json.loads((resources / ('assets/antagon_inventory/lang/' + locale + '.json')).read_text())
               for locale in ('pt_br', 'en_us')]
    if set(locales[0]) != set(locales[1]):
        raise RuntimeError('Traduções inconsistentes.')
    if locales[0] != locales[1]:
        raise RuntimeError('Todos os textos do mod devem permanecer em português, inclusive no idioma alternativo.')
    temporary = ARTIFACT.with_suffix('.jar.building')
    with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(classes.rglob('*.class')):
            archive.write(file, file.relative_to(classes).as_posix())
        for file in sorted(resources.rglob('*')):
            if file.is_file():
                archive.write(file, file.relative_to(resources).as_posix())
        archive.write(ROOT / 'LICENSE', 'LICENSE')
    os.replace(temporary, ARTIFACT)
    with zipfile.ZipFile(ARTIFACT) as archive:
        if archive.testzip() is not None:
            raise RuntimeError('JAR inválido.')
    manifest = metadata(ARTIFACT)
    if manifest['environment'] != 'client' or manifest['id'] != 'antagon_inventory':
        raise RuntimeError('Metadados do mod inválidos.')
    print('Compilado:', ARTIFACT)
    receipt = {'minecraft': '26.3', 'version': manifest['version'], 'sha256': digest(ARTIFACT),
               'tests': test.stdout.strip() + '\n' + drag_test.stdout.strip() + '\n' + config_test.stdout.strip() + '\n' + '\n'.join(
                   line for line in storage_test.stdout.splitlines() if 'OK:' in line), 'mixin_targets_verified': True,
               'runtime_validation': 'Not launched; restart the game to load the installed mod.'}
    if args.install:
        mods = GAME / 'mods'
        current = [(file, metadata(file)) for file in mods.glob('*.jar')]
        removable = {'inventoryprofilesnext', 'libipn', 'fabric-language-kotlin', 'antagon_inventory'}
        # Keep support libraries when an unrelated installed mod needs them.
        while True:
            previous = set(removable)
            for file, m in current:
                if m['id'] not in removable:
                    for dependency in m.get('depends', {}):
                        if dependency != 'antagon_inventory':
                            removable.discard(dependency)
            if removable == previous:
                break
        if 'inventoryprofilesnext' not in removable:
            raise RuntimeError('Outro mod depende do IPN. Não foi possível substituir sem afetar essa dependência.')
        replaced = [file for file, m in current if m['id'] in removable]
        untouched = {file.name: digest(file) for file, m in current if m['id'] not in removable}
        backup = GAME / ('antagon-mod-backups/antagon-inventory/' + str(time.time_ns()))
        backup.mkdir(parents=True)
        for file in replaced:
            shutil.copy2(file, backup / file.name)
        target = mods / ARTIFACT.name
        pending = target.with_suffix('.jar.installing')
        if pending.exists():
            raise RuntimeError('Instalação anterior incompleta: ' + str(pending))
        shutil.copy2(ARTIFACT, pending)
        if digest(pending) != receipt['sha256']:
            raise RuntimeError('Falha na cópia do JAR.')
        try:
            for file in replaced:
                file.unlink()
            os.replace(pending, target)
            if digest(target) != receipt['sha256']:
                raise RuntimeError('Falha na verificação do mod instalado.')
        except Exception:
            if target.exists() and target not in replaced:
                target.unlink()
            for file in replaced:
                shutil.copy2(backup / file.name, file)
            if pending.exists():
                pending.unlink()
            raise
        for name, expected in untouched.items():
            if digest(mods / name) != expected:
                raise RuntimeError('Um mod não relacionado mudou durante a instalação: ' + name)
        receipt.update(installed=str(target), archived=[str(file) for file in replaced],
                       preserved_mods_sha256=untouched, backup=str(backup))
        (backup / 'instalacao.json').write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + '\n')
        print('Instalado:', target)
        print('Mods anteriores arquivados:', len(replaced), '| Outros mods preservados:', len(untouched))
    (BUILD / 'validation.json').write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + '\n')


if __name__ == '__main__':
    main()
