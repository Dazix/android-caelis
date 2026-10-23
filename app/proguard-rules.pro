# R8 rules of the app itself. The libraries bring their own (Filament's covers what its native code
# finds by name, ARCore, CameraX, Compose and DataStore ship consumer rules), and the components the
# manifest names are kept by AGP. Nothing in app/src/main uses reflection, so this file adds no keeps:
# a rule here is a decision that something is looked up by name, and it says what and why.
#
# The one lookup by name is a resource, not a class: NightSkyScene / NightSkyRenderer find the
# constellation and DSO artwork with Resources.getIdentifier from catalogue names. Those drawables
# are kept against resource shrinking in res/raw/keep.xml.

# Line numbers for Play's crash reports, under a file name that says nothing about the source layout.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
