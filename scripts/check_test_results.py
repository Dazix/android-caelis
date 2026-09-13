#!/usr/bin/env python3
import glob
import os
import sys
import xml.etree.ElementTree as ET

def find_xml_files(args):
    files = []
    if not args:
        args = ["app/build/test-results/testDebugUnitTest"]
    
    for arg in args:
        if os.path.isdir(arg):
            matched = glob.glob(os.path.join(arg, "**", "*.xml"), recursive=True)
            files.extend(matched)
        elif os.path.isfile(arg):
            files.append(arg)
        else:
            matched = glob.glob(arg, recursive=True)
            files.extend(matched)
            
    # Deduplicate while preserving order
    seen = set()
    unique_files = []
    for f in files:
        if f not in seen:
            seen.add(f)
            unique_files.append(f)
    return unique_files

def main():
    xml_files = find_xml_files(sys.argv[1:])
    if not xml_files:
        print("no unit-test result XML at all - the tests did not run", file=sys.stderr)
        sys.exit(1)

    total_tests = 0
    total_failures = 0
    total_errors = 0
    total_skipped = 0

    for path in xml_files:
        try:
            tree = ET.parse(path)
            root = tree.getroot()
            # Support both <testsuite> root and <testsuites> container
            if root.tag == "testsuite":
                total_tests += int(root.attrib.get("tests", 0))
                total_failures += int(root.attrib.get("failures", 0))
                total_errors += int(root.attrib.get("errors", 0))
                total_skipped += int(root.attrib.get("skipped", 0))
            else:
                for suite in root.findall(".//testsuite"):
                    total_tests += int(suite.attrib.get("tests", 0))
                    total_failures += int(suite.attrib.get("failures", 0))
                    total_errors += int(suite.attrib.get("errors", 0))
                    total_skipped += int(suite.attrib.get("skipped", 0))
        except Exception as e:
            print(f"Error parsing XML file '{path}': {e}", file=sys.stderr)
            sys.exit(1)

    print(f"tests={total_tests} failures={total_failures} errors={total_errors} skipped={total_skipped} across {len(xml_files)} file(s)")

    bad = []
    if total_tests == 0:
        bad.append("no tests ran")
    if total_failures:
        bad.append(f"{total_failures} failed")
    if total_errors:
        bad.append(f"{total_errors} errored")
    if total_skipped:
        bad.append(f"{total_skipped} skipped - a skip is a test that did not answer the question")

    if bad:
        sys.exit("unit tests: " + "; ".join(bad))

if __name__ == "__main__":
    main()
