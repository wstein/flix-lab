# JRuby sibling of ../../../../../../../src/javalib/dev/wstein/flixlab/Greeter.java -- see that
# class's doc comment for why the nested-call shape matters for a debug session. Greeter.java (next
# to this file on the classpath) only boots the JRuby runtime; this is where the greeting itself is
# built.

def subject
  "JRuby"
end

# The subject of the greeting. Separate so it can be stepped into from greeting.
def greeting
  subject = subject()
  "Hello #{subject}!"
end

greeting
