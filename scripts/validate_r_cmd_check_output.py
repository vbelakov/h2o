#!/usr/bin/python

#
# CRAN submissions use the R CMD CHECK --as-cran approach.
# But that unfortunately does not do a good job of flagging errors in a way that can be automated.
#
# This tool goes combs through the output and returns 0 if it's good and nonzero if it's bad.
#

import sys
import os
import re


class Check:
    def __init__(self, file_name):
        self.file_name = file_name
        self.lineno = 0

    def parse_error(self, message, s, f):
        print("ERROR " + message + " " + self.file_name + " line " + str(self.lineno))
        sys.stdout.write("    >>> " + s)
        s = f.readline()
        while (len(s) > 0):
            sys.stdout.write("    >>> " + s)
            s = f.readline()
        sys.exit(1)

    def process(self):
        # print("Processing " + self.file_name + "...")

        f = open(self.file_name, "r")

        allowed_regex_list = [
            r"^\* using log directory",
            r"^\* using R version",
            r"^\* using R version",
            r"^\* using platform",
            r"^\* using session charset",
            r"^\* using option .*",
            r"^\* checking .* \.\.\. OK",
            r"^\* checking extension type \.\.\. Package",
            r"^\* this is package",
            r"^\* checking CRAN incoming feasibility \.\.\. NOTE",
            r"^Maintainer:",
            r"^New maintainer:",
            r"^\s*Tom Kraljevic",
            r"^\Days since last update: .*",
            r"^Old maintainer\(s\):",
            r"^\s*Anqi Fu",
            r"^NOTE: There was 1 note.",
            r"^The Title field starts with the package name.",
            r"^The Date field is over a month old.",

            r"^\n",
            r"^New submission",

            r"^Package was archived on CRAN",
            r"^CRAN repository db overrides:",
            r"^  X-CRAN-Comment: Archived on 2014-09-23 as did not comply with CRAN",
            r"^    policies on use of multiple threads.",

            r"^\* checking installed package size ... NOTE",
            r"^  installed size is .*Mb",
            r"^  sub-directories of 1Mb or more:",
            r"^    java  .*Mb",
            r"^NOTE: There were 2 notes.",

            r"^Package has FOSS license, installs .class/.jar but has no 'java' directory.",
            r"^\* DONE",

            r"^Checking URLs requires 'libcurl' support in the R build",
            r"^Status: 2 NOTEs",
        ]

        s = f.readline()
        while (len(s) > 0):
            self.lineno = self.lineno + 1

            allowed = False
            for regex in allowed_regex_list:
                match_groups = re.search(regex, s)
                if (match_groups is not None):
                    # This line is allowed.
                    allowed = True
                    break

            if (not allowed):
                self.parse_error("Illegal output found", s, f)

            s = f.readline()


def main(argv):
    if (not os.path.exists("h2o.Rcheck")):
        print("ERROR:  You must run this script inside the generated R package source directory.")
        sys.exit(1)

    c = Check("h2o.Rcheck/00check.log")
    c.process()

    # No failures.
    sys.exit(0)


if __name__ == "__main__":
    main(sys.argv)
