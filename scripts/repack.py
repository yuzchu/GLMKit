#!/usr/bin/env python3
"""
改包名重打包工具
用法: python3 repack.py <input.apk> <new_package> [output.apk]

原理:
1. 解压 APK
2. 修改 AndroidManifest.xml 中的 package 名
3. 重新打包 + zipalign + 签名
"""

import sys
import os
import struct
import zipfile
import shutil
import subprocess
from pathlib import Path

# Android binary XML constants
RES_XML_TYPE = 0x0003
RES_STRING_POOL_TYPE = 0x0001
CHUNK_HEADER_SIZE = 8

def read_u16(data, off):
    return struct.unpack_from('<H', data, off)[0]

def read_u32(data, off):
    return struct.unpack_from('<I', data, off)[0]

def write_u32(data, off, val):
    struct.pack_into('<I', data, off, val)

def write_u16(data, off, val):
    struct.pack_into('<H', data, off, val)

def modify_axml_package(axml_data, old_pkg, new_pkg):
    """修改二进制 AndroidManifest.xml 中的 package 名"""
    if old_pkg == new_pkg:
        return axml_data, False
    
    data = bytearray(axml_data)
    
    # Parse chunks
    offset = 0
    total_len = len(data)
    
    while offset < total_len - 8:
        chunk_type = read_u16(data, offset + 2)
        chunk_size = read_u32(data, offset + 4)
        
        if chunk_type == RES_STRING_POOL_TYPE:
            # Found string pool
            pool_start = offset
            string_count = read_u32(data, pool_start + 8)
            style_count = read_u32(data, pool_start + 12)
            flags = read_u32(data, pool_start + 16)
            strings_start = read_u32(data, pool_start + 20)
            styles_start = read_u32(data, pool_start + 24)
            
            is_utf8 = (flags & (1 << 8)) != 0
            
            # Read string offsets
            string_offsets = []
            for i in range(string_count):
                str_off = read_u32(data, pool_start + 28 + i * 4)
                string_offsets.append(str_off)
            
            # Find and replace the package name string
            replaced = False
            for i, str_off in enumerate(string_offsets):
                abs_off = pool_start + strings_start + str_off
                
                if is_utf8:
                    # UTF-8 string: [len1] [len2] [chars] [0]
                    # len encoding: if len < 0x80, one byte; else two bytes
                    b1 = data[abs_off]
                    if b1 & 0x80:
                        str_len = ((b1 & 0x7F) << 8) | data[abs_off + 1]
                        chars_start = abs_off + 2
                    else:
                        str_len = b1
                        chars_start = abs_off + 1
                    
                    try:
                        s = data[chars_start:chars_start + str_len].decode('utf-8')
                    except:
                        continue
                    
                    if s == old_pkg:
                        # Replace with new_pkg
                        new_bytes = new_pkg.encode('utf-8')
                        new_len = len(new_bytes)
                        
                        # Build new string entry
                        if new_len < 0x80:
                            new_entry = bytes([new_len]) + new_bytes + b'\x00'
                        else:
                            new_entry = bytes([0x80 | (new_len >> 8), new_len & 0xFF]) + new_bytes + b'\x00'
                        
                        old_entry_len = str_len + 2 if str_len >= 0x80 else str_len + 2
                        old_entry = data[abs_off:abs_off + old_entry_len]
                        
                        # Replace in data
                        new_data = data[:abs_off] + bytearray(new_entry) + data[abs_off + len(old_entry):]
                        data = new_data
                        replaced = True
                        print(f"  ✓ 替换 UTF-8 字符串 #{i}: {old_pkg} → {new_pkg}")
                        break
                else:
                    # UTF-16 string: [len1] [len2] [chars] [0] [0]
                    b1 = read_u16(data, abs_off)
                    if b1 & 0x8000:
                        str_len = ((b1 & 0x7FFF) << 16) | read_u16(data, abs_off + 2)
                        chars_start = abs_off + 4
                    else:
                        str_len = b1
                        chars_start = abs_off + 2
                    
                    try:
                        s = data[chars_start:chars_start + str_len * 2].decode('utf-16-le')
                    except:
                        continue
                    
                    if s == old_pkg:
                        # Replace with new_pkg (UTF-16)
                        new_bytes = new_pkg.encode('utf-16-le')
                        new_len = len(new_pkg)
                        
                        if new_len < 0x8000:
                            new_header = struct.pack('<H', new_len)
                        else:
                            new_header = struct.pack('<HH', 0x8000 | (new_len >> 16), new_len & 0xFFFF)
                        
                        new_entry = new_header + new_bytes + b'\x00\x00'
                        old_entry_len = (str_len * 2 + 4) if str_len >= 0x8000 else (str_len * 2 + 2)
                        old_entry = data[abs_off:abs_off + old_entry_len + 2]
                        
                        new_data = data[:abs_off] + bytearray(new_entry) + data[abs_off + len(old_entry):]
                        data = new_data
                        replaced = True
                        print(f"  ✓ 替换 UTF-16 字符串 #{i}: {old_pkg} → {new_pkg}")
                        break
            
            if not replaced:
                print(f"  ⚠ 未在 string pool 中找到 '{old_pkg}'")
            
            return bytes(data), replaced
        
        if chunk_size == 0 or chunk_size > total_len:
            break
        offset += chunk_size
    
    return axml_data, False

def get_package_name(axml_data):
    """从二进制 manifest 中读取 package 名"""
    try:
        from pyaxmlparser import AXMLError
        import pyaxmlparser
        # pyaxmlparser can parse the manifest
        return None  # We'll use a simpler approach
    except:
        pass
    
    # Simple approach: look for common package patterns in the string pool
    data = axml_data
    offset = 0
    total_len = len(data)
    
    while offset < total_len - 8:
        chunk_type = read_u16(data, offset + 2)
        chunk_size = read_u32(data, offset + 4)
        
        if chunk_type == RES_STRING_POOL_TYPE:
            pool_start = offset
            string_count = read_u32(data, pool_start + 8)
            flags = read_u32(data, pool_start + 16)
            strings_start = read_u32(data, pool_start + 20)
            is_utf8 = (flags & (1 << 8)) != 0
            
            for i in range(min(string_count, 200)):
                str_off = read_u32(data, pool_start + 28 + i * 4)
                abs_off = pool_start + strings_start + str_off
                
                try:
                    if is_utf8:
                        b1 = data[abs_off]
                        if b1 & 0x80:
                            str_len = ((b1 & 0x7F) << 8) | data[abs_off + 1]
                            chars_start = abs_off + 2
                        else:
                            str_len = b1
                            chars_start = abs_off + 1
                        s = data[chars_start:chars_start + str_len].decode('utf-8')
                    else:
                        b1 = read_u16(data, abs_off)
                        if b1 & 0x8000:
                            str_len = ((b1 & 0x7FFF) << 16) | read_u16(data, abs_off + 2)
                            chars_start = abs_off + 4
                        else:
                            str_len = b1
                            chars_start = abs_off + 2
                        s = data[chars_start:chars_start + str_len * 2].decode('utf-16-le')
                    
                    # Look for package-like strings (has dots, looks like a package)
                    if '.' in s and len(s) > 5 and len(s) < 60 and not s.startswith('http') and not s.startswith('android'):
                        if any(c.isupper() for c in s) or s.count('.') >= 2:
                            return s
                except:
                    continue
            break
        
        if chunk_size == 0 or chunk_size > total_len:
            break
        offset += chunk_size
    
    return None

def repack_apk(input_apk, new_package, output_apk=None, old_package=None):
    """重打包 APK，修改包名"""
    input_apk = Path(input_apk)
    if output_apk is None:
        stem = input_apk.stem
        output_apk = input_apk.parent / f"{stem}_repacked.apk"
    else:
        output_apk = Path(output_apk)
    
    print(f"📦 输入: {input_apk}")
    print(f"📦 输出: {output_apk}")
    print(f"📦 新包名: {new_package}")
    
    # Step 1: Read the APK
    with zipfile.ZipFile(input_apk, 'r') as zin:
        # Read AndroidManifest.xml
        manifest_data = zin.read('AndroidManifest.xml')
        
        # Auto-detect old package name
        if old_package is None:
            old_package = get_package_name(manifest_data)
            if old_package:
                print(f"📦 原包名: {old_package}")
            else:
                print("⚠ 无法自动检测原包名，请手动指定")
                return False
        
        # Step 2: Modify the manifest
        print(f"\n🔧 修改 AndroidManifest.xml...")
        new_manifest, replaced = modify_axml_package(manifest_data, old_package, new_package)
        
        if not replaced:
            print("⚠ 包名替换失败！")
            return False
        
        # Step 3: Write new APK
        print(f"\n📝 写入新 APK...")
        tmp_apk = output_apk.with_suffix('.tmp.apk')
        with zipfile.ZipFile(input_apk, 'r') as zin:
            with zipfile.ZipFile(tmp_apk, 'w', zipfile.ZIP_DEFLATED) as zout:
                for item in zin.infolist():
                    if item.filename == 'AndroidManifest.xml':
                        zout.writestr(item, new_manifest)
                    else:
                        zout.writestr(item, zin.read(item.filename))
        
        # Step 4: zipalign
        print(f"\n📐 zipalign...")
        aligned_apk = output_apk.with_suffix('.aligned.apk')
        aapt_dir = '/root/android-sdk/build-tools/34.0.0'
        result = subprocess.run(
            [f'{aapt_dir}/zipalign', '-f', '4', str(tmp_apk), str(aligned_apk)],
            capture_output=True, text=True
        )
        if result.returncode != 0:
            print(f"⚠ zipalign 失败: {result.stderr}")
            return False
        
        # Step 5: Sign
        print(f"\n✍ 签名...")
        result = subprocess.run(
            [f'{aapt_dir}/apksigner', 'sign',
             '--ks', '/root/glm-mod/debug.keystore',
             '--ks-key-alias', 'androiddebugkey',
             '--ks-pass', 'pass:android',
             '--key-pass', 'pass:android',
             str(aligned_apk)],
            capture_output=True, text=True
        )
        if result.returncode != 0:
            print(f"⚠ 签名失败: {result.stderr}")
            return False
        
        # Move to final output
        shutil.move(str(aligned_apk), str(output_apk))
        tmp_apk.unlink(missing_ok=True)
        
        # Verify
        result = subprocess.run(
            [f'{aapt_dir}/apksigner', 'verify', str(output_apk)],
            capture_output=True, text=True
        )
        
        size_kb = output_apk.stat().st_size // 1024
        print(f"\n✅ 重打包完成!")
        print(f"   文件: {output_apk}")
        print(f"   大小: {size_kb} KB")
        print(f"   签名: {'✓ 验证通过' if result.returncode == 0 else '⚠ 验证失败'}")
        print(f"   包名: {old_package} → {new_package}")
        
        return True

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("用法: python3 repack.py <input.apk> <new_package> [output.apk] [old_package]")
        print("示例: python3 repack.py deepseek.apk com.deepseek.chat.clone")
        sys.exit(1)
    
    input_apk = sys.argv[1]
    new_package = sys.argv[2]
    output_apk = sys.argv[3] if len(sys.argv) > 3 else None
    old_package = sys.argv[4] if len(sys.argv) > 4 else None
    
    success = repack_apk(input_apk, new_package, output_apk, old_package)
    sys.exit(0 if success else 1)
