Ktl - collection of 
controls with
- rawValue,
- value (normalised value),
- hardware scene,
- semantic description of the control
- spec (how to map)

- deals with hardware protocols

Dispatch - 



//Ktl - containing some sliders
k = Ktl.new;
k.elements = [\slider1,\slider2,\slider3]

a = (); // pseudoktl

// Dispatch
d = Dispatch.new;

d.mapToCtl( a, \slider1, \nano );
d.mapToCtl( a, \slider2, \nano );
d.mapToCtl( a, \slider3, \nano );

now \slider1 when a value changes will call
.value

slider -> value( ktlname, slider, value )

d.value( a )

d.map( source, ctl, name );
value->
(source,ctl)-pairs to names;

d.map( \nanoktl, \slider1, \myslidervalue );
d.map( \guinano, \slider1, \myslidervalue );

( \me -> this Dispatch )
( \source1 -> Ktl )
sources
source1 -> collection of ctls
   ctl -> name

d.mapsource( \source1, d )

d.addFunction( \setSlider1, {
	d.setOutput( \slider1, 0.5 );
});

d.addFunction( \average, { |value,ctl,dis,ktl| 
	envir.put( \average, [\slider1,\slider2,\slider3].collect{ |it| envir[it] }/3 );
	[\average,\otherstuff]
} );


envir[\myslidervalue]

look up name for source, ctl
envir.name = value

d.addFunction( \average, { |value,ctl,dis,ktl| 
	envir.put( \average, [\slider1,\slider2,\slider3].collect{ |it| envir[it] }/3 );
	[\average,\otherstuff]
} );

d.addFunction( \average, { |value,ctl,dis,ktl| 
	envir.put( \average, [\slider1,\slider2,\slider3].collect{ |it| envir[it] }/3 );
	dis.setOutput( \average, val )
} );

// initialize the scene
d.set( \scene, 0 );
d.addFunction( \setScene, { |ktl,key,value,envir| 
	if ( key == \button1 ){
		// select one of five scenes;
		envir.put( \scene, (envir[\scene] + 1).wrap(0,5) );
	};
} );

~d2 = Dispatch.new;
d.addFunction( \dispatcher2, ~d2 );

// all values of the ktl in an array
ktl.elements.collect{ |it| it.value };

