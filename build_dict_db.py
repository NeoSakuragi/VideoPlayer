#!/usr/bin/env python3
"""Build the dictionary SQLite database from Yomitan zip files."""

import json
import os
import sqlite3
import sys
import zipfile

DB_PATH = "app/src/main/assets/dictionary.db"
JITENDEX_ZIP = "app/src/main/assets/jitendex.zip"
JPDB_ZIP = "app/src/main/assets/jpdb_freq.zip"


def extract_meanings(defs):
    """Extract plain-text meanings from Yomitan definition structures."""
    if isinstance(defs, str):
        return defs
    if isinstance(defs, list):
        meanings = []
        for d in defs:
            if isinstance(d, str):
                meanings.append(d)
            elif isinstance(d, dict):
                t = d.get("type", "")
                if t == "text":
                    meanings.append(d.get("text", ""))
                elif t == "structured-content":
                    meanings.append(extract_structured(d.get("content", "")))
        return "\n".join(meanings)
    return str(defs)


def extract_structured(content):
    """Recursively extract text from structured content."""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "".join(extract_structured(c) for c in content)
    if isinstance(content, dict):
        tag = content.get("tag", "")
        inner = content.get("content", "")
        text = extract_structured(inner) if inner else ""
        if tag == "br":
            return "\n"
        if tag == "li":
            return f"- {text}\n"
        if tag in ("rt", "rp"):
            return ""
        return text
    return ""


def import_jitendex(cur, zip_path):
    print(f"Importing JitEndex from {zip_path}...")
    count = 0
    with zipfile.ZipFile(zip_path) as zf:
        for name in sorted(zf.namelist()):
            if not name.startswith("term_bank_") or not name.endswith(".json"):
                continue
            data = json.loads(zf.read(name))
            for entry in data:
                term = entry[0]
                reading = entry[1] or term
                tags = entry[2] if len(entry) > 2 else ""
                score = entry[4] if len(entry) > 4 else 0
                meanings = extract_meanings(entry[5]) if len(entry) > 5 else ""
                sequence = entry[6] if len(entry) > 6 else 0
                cur.execute(
                    "INSERT INTO dict_entries(term,reading,tags,score,meanings,sequence) VALUES(?,?,?,?,?,?)",
                    (term, reading, tags, score, meanings, sequence),
                )
                count += 1
            if count % 50000 == 0:
                print(f"  {count} entries...")
    print(f"  JitEndex done: {count} entries")
    return count


def import_jpdb_freq(cur, zip_path):
    print(f"Importing JPDB frequencies from {zip_path}...")
    count = 0
    with zipfile.ZipFile(zip_path) as zf:
        for name in sorted(zf.namelist()):
            if not name.startswith("term_meta_bank_") or not name.endswith(".json"):
                continue
            data = json.loads(zf.read(name))
            for entry in data:
                term = entry[0]
                mode = entry[1]
                if mode != "freq":
                    continue
                freq_data = entry[2]
                reading = None
                freq = 0
                if isinstance(freq_data, (int, float)):
                    freq = int(freq_data)
                elif isinstance(freq_data, str):
                    freq = int(freq_data) if freq_data.isdigit() else 0
                elif isinstance(freq_data, dict):
                    reading = freq_data.get("reading")
                    fv = freq_data.get("frequency", freq_data.get("value", 0))
                    if isinstance(fv, dict):
                        freq = fv.get("value", 0)
                    else:
                        freq = int(fv) if fv else 0
                cur.execute(
                    "INSERT INTO frequencies(term,reading,freq) VALUES(?,?,?)",
                    (term, reading, freq),
                )
                count += 1
    print(f"  JPDB done: {count} entries")
    return count


def main():
    if os.path.exists(DB_PATH):
        os.remove(DB_PATH)

    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()

    cur.execute("""
        CREATE TABLE dict_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            term TEXT NOT NULL,
            reading TEXT NOT NULL,
            tags TEXT,
            score INTEGER DEFAULT 0,
            meanings TEXT NOT NULL,
            sequence INTEGER DEFAULT 0
        )
    """)
    cur.execute("""
        CREATE TABLE frequencies (
            term TEXT NOT NULL,
            reading TEXT,
            freq INTEGER NOT NULL
        )
    """)
    cur.execute("""
        CREATE TABLE dict_meta (
            key TEXT PRIMARY KEY,
            value TEXT
        )
    """)

    dict_count = import_jitendex(cur, JITENDEX_ZIP)
    freq_count = import_jpdb_freq(cur, JPDB_ZIP)

    # Create indexes after bulk insert (faster)
    print("Creating indexes...")
    cur.execute("CREATE INDEX idx_dict_term ON dict_entries(term)")
    cur.execute("CREATE INDEX idx_dict_reading ON dict_entries(reading)")
    cur.execute("CREATE INDEX idx_freq_term ON frequencies(term)")

    # Mark as loaded
    cur.execute("INSERT INTO dict_meta(key,value) VALUES('loaded_jitendex','true')")
    cur.execute("INSERT INTO dict_meta(key,value) VALUES('loaded_jpdb_freq','true')")

    conn.commit()
    conn.close()

    size_mb = os.path.getsize(DB_PATH) / 1_048_576
    print(f"\nDone! {DB_PATH}: {size_mb:.1f} MB")
    print(f"  {dict_count} dictionary entries")
    print(f"  {freq_count} frequency entries")


if __name__ == "__main__":
    main()
