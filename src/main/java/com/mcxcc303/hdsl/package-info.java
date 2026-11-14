/**
 * HDSL's own layer: what the launcher is for, as opposed to the interface it is drawn with.
 *
 * The packages below are arranged by subject.  They stay clear of JavaFX and of the transplanted
 * interface packages, which is what lets the command line and the tests run without a toolkit
 * and keeps the interface layer on one side of the line; only the classes that have to present
 * something cross over, namely the instance icons, the skins and the report of a crashed process.
 *
 * The pages that present them live in the interface tree, under its `dsh` package.
 */
package com.mcxcc303.hdsl;
