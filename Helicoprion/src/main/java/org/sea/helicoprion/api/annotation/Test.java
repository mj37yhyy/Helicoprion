package org.sea.helicoprion.api.annotation;

@HeliServer(path = "/Test")
public class Test {

	@HeliServer(path = "/helloWorld")
	public @ToJSON
	String helloWorld(@FromJSON String name) {
		return "Hello World";
	}

}
