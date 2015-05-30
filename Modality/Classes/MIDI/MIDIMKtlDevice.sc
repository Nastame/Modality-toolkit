MIDIMKtlDevice : MKtlDevice {

	classvar <allMsgTypes = #[ \noteOn, \noteOff, \noteOnOff, \cc, \touch, \polyTouch, \bend, \program ];

	classvar <protocol = \midi;
	classvar <initialized = false;
			//      ('deviceName': MIDIEndPoint, ... )
			// e.g.  ( 'bcr0': MIDIEndPoint("BCR2000", "Port 1"), ... )
	classvar <sourceDeviceDict;
			//      ('deviceName': MIDIEndPoint, ... )
	 		//i.e.  ( 'bcr0': MIDIEndPoint("BCR2000", "Port 2"), ... )
	classvar <destinationDeviceDict;

	// MIDI-specific address identifiers
	var <srcID /*Int*/, <source /*MIDIEndPoint*/;
	var <dstID /*Int*/, <destination /*MIDIEndPoint*/, <midiOut /*MIDIOut*/;

	// an action that is called every time a midi message comes in
	// .value(type, src, chan, num/note, value/vel)

	// optimisation for fast lookup in two dicts:
	var <midiKeyToElemDict;    // find element by e.g. midiCCKey

	// an action that is called every time a midi message comes in
	// .value(type, src, chan, num/note, value/vel)
	var <>midiRawAction;

	// a dictionary of actions for incoming MIDI messages by type
	var <global;
	var <responders; // the MIDIFuncs responding to each protocol
	var <msgTypes;	// the msgTypes for which this MKtl needs MIDIfuncs


	// could use some reorganisation...

	closeDevice {
		destination.notNil.if{
			if ( thisProcess.platform.name == \linux ){
				midiOut.disconnect( MIDIClient.destinations.indexOf(destination) )
			};
			midiOut = nil;
		};
		source = nil;
		destination = nil;
	}

	// open all ports
	*initDevices { |force= false|

		(initialized && {force.not}).if{^this};

		// workaround for inconsistent behaviour between linux and osx
		if ( MIDIClient.initialized and: (thisProcess.platform.name == \linux) ){
			MIDIClient.disposeClient;
			MIDIClient.init;
		};
		if ( thisProcess.platform.name == \osx and: Main.versionAtMost( 3,6 ) ){
			"next time you recompile the language, reboot the interpreter instead to get MIDI working again.".warn;
		};

		MIDIIn.connectAll;
		sourceDeviceDict = ();
		destinationDeviceDict = ();

		this.prepareDeviceDicts;

		initialized = true;
	}

		// display all ports in readable fashion,
		// copy/paste-able directly
		// this could also live in /--where?--/
	*find { |post=true|
		this.initDevices( true );

		if ( MIDIClient.sources.isEmpty and: MIDIClient.destinations.isEmpty ) {
			"// MIDIMKtl did not find any sources or destinations - "
			"// you may want to connect some first.".inform;
			^this
		};

		if ( post ){
			this.postPossible;
		};
	}

	*getIDInfoFrom { |lookupName|
		var endpoint = MIDIMKtlDevice.sourceDeviceDict[lookupName];
		^endpoint !? { endpoint.device }
	}


	*postPossible {

		"\n-----------------------------------------------------".postln;
		"\n// Available MIDIMKtls: ".postln;
		"// MKtl(autoName, filename);  // [ midi device, midi port ]".postln;
		sourceDeviceDict.keysValuesDo { |key, src|
			var deviceName = src.device;
			var midiPortName = src.name;
			var postList = [deviceName, midiPortName];
			var filename = MKtlDesc.filenameForIDInfo(deviceName);
			filename = if (filename.isNil) { "" } { "," + quote(filename) };

			"    MKtl(%%);  // % \n".postf(
				key.cs, filename, postList
			);
		};
		"\n-----------------------------------------------------".postln;
	}

	*getSourceName { |shortName|
		var srcName;
		var src = this.sourceDeviceDict.at( shortName );
		if ( src.notNil ){
			srcName = src.device;
		}{
			src = this.destinationDeviceDict.at( shortName );
			if ( src.notNil ){
				srcName = src.device;
			};
		};
		^srcName;
	}

	*findSource { |rawDeviceName, rawPortName| // or destination
		var devKey;
		if ( initialized.not ){ ^nil };
		this.sourceDeviceDict.keysValuesDo{ |key,endpoint|
			if ( endpoint.device == rawDeviceName ){
				if ( rawPortName.isNil ){
					devKey = key;
				}{
					if ( endpoint.name == rawPortName ){
						devKey = key;
					}
				}
			};
		};
		if ( devKey.isNil ){
			this.destinationDeviceDict.keysValuesDo{ |key,endpoint|
				if ( endpoint.device == rawDeviceName ){
					if ( rawPortName.isNil ){
						devKey = key;
					}{
						if ( endpoint.name == rawPortName ){
							devKey = key;
						}
					}
				};
			};
		};
		^devKey;
	}

	// create with a uid, or access by name
	*new { |name, srcUID, destUID, parentMKtl|
		var foundSource, foundDestination;
		var deviceName;

		this.initDevices;

		// make a new source
		foundSource = srcUID.notNil.if({
			MIDIClient.sources.detect { |src|
				src.uid == srcUID;
			};
		}, {
			sourceDeviceDict[name.asSymbol];
		});

		if (foundSource.isNil) {
			warn("MIDIMKtlDevice:"
			"	No MIDIIn source with USB port ID % exists! please check again.".format(srcUID));
		};

		// make a new destination
		foundDestination = destUID.notNil.if({
			MIDIClient.destinations.detect { |src|
				src.uid == destUID;
			};
		}, {
			destinationDeviceDict[name.asSymbol];
		});

		if (foundDestination.isNil) {
			warn("MIDIMKtlDevice:"
				"	No MIDIOut destination with USB port ID % exists!"
				"please check again.".format(destUID));
		};

				// if ( foundSource.isNil and: foundDestination.isNil ){
				// warn("MIDIMKtl:"
				// "	No MIDIIn source nor destination with USB port ID %, % exists! please check again.".format(srcUID, destUID));
				// ^nil;
				// };

		if (foundDestination.notNil) {
			destinationDeviceDict.changeKeyForValue(name, foundDestination);
			deviceName = foundDestination.device;
		};
		if (foundSource.notNil) {
			sourceDeviceDict.changeKeyForValue(name, foundSource);
			deviceName = foundSource.device;
		};

		^super.basicNew(name, deviceName, parentMKtl )
			.initMIDIMKtl(name, foundSource, foundDestination );
	}

	*prepareDeviceDicts {
		var prevName = nil, j = 0, order, deviceNames;
		var tempName;

		deviceNames = MIDIClient.sources.collect {|src|
			tempName = src.device;
			MKtl.makeLookupName(tempName);
		};

		if (deviceNames.isEmpty) {
			^this
		};

		order = deviceNames.order;
		deviceNames[order].do {|name, i|
			(prevName == name).if({
				j = j+1;
			},{
				j = 0;
			});
			prevName = name;

			sourceDeviceDict.put((name ++ j).asSymbol,
				MIDIClient.sources[order[i]])
		};

		// prepare destinationDeviceDict
		j = 0; prevName = nil;
		deviceNames = MIDIClient.destinations.collect{|src|
			tempName = src.device;
			MKtl.makeLookupName(tempName);
		};
		order = deviceNames.order;

		deviceNames[order].do{|name, i|
			(prevName == name).if({
				j = j+1;
			},{
				j = 0;
			});
			prevName = name;

			destinationDeviceDict.put((name ++ j).asSymbol,
				MIDIClient.destinations[order[i]])
		};

		// put the available midi devices in MKtl's available devices
		allAvailable.put( \midi, List.new );
		sourceDeviceDict.keysDo({ |key|
			allAvailable[\midi].add( key );
		});
	}

	/// ----(((((----- EXPLORING ---------

	exploring {
		^(MIDIExplorer.observedSrcID == srcID );
	}

	explore { |mode=true|
		if ( mode ){
			"Using MIDIExplorer. (see its Helpfile for Details)".postln;
			"".postln;
			"MIDIExplorer started. Wiggle all elements of your controller then".postln;
			"\tMKtl(%).explore(false);\n".postf( name );
			"\tMKtl(%).createDescriptionFile;\n".postf( name );
			MIDIExplorer.start(this.srcID);
		} {
			MIDIExplorer.stop;
			"MIDIExplorer stopped.".postln;
		}
	}

	createDescriptionFile {
		MIDIExplorer.openDoc;
	}

	/// --------- EXPLORING -----)))))---------

	initElements {
		"initElements".postln;
		if ( mktl.elementsDict.isNil ){
			warn("" + mktl + "has no elementsDict?");
			^this;
		};
		this.prepareLookupDicts;
		msgTypes = mktl.desc.fullDesc[\msgTypesUsed];
		this.makeRespFuncs;
	}

	// nothing here yet, but needed
	initCollectives {

	}

	initMIDIMKtl { |argName, argSource, argDestination|
		// [argName, argSource, argDestination].postln;
		name = argName;
		"initMIDIMKtl".postln;
		source = argSource;
		source.notNil.if { srcID = source.uid };

		// destination is optional
		destination = argDestination;
		destination.notNil.if{
			dstID = destination.uid;
			midiOut = MIDIOut( MIDIClient.destinations.indexOf(destination), dstID );

			// set latency to zero as we assume to have controllers
			// rather than synths connected to the device.
			midiOut.latency = 0;

			if ( thisProcess.platform.name == \linux ){
				midiOut.connect( MIDIClient.destinations.indexOf(destination) )
			};
		};



		"this.initCollectives;".postln;
		this.initCollectives;
		"this.sendInitialisationMessages;".postln;
		this.sendInitialisationMessages;
		this
	}

	makeHashKey { |elemDesc, elem|

		var msgType = elemDesc[\midiMsgType];
		var hashes;

		if( allMsgTypes.includes(msgType).not ) {
			warn("% has unsupported \\midiMsgType: %".format(elem, msgType));
			^this
		};

		hashes = msgType.switch(
			\noteOn, { this.makeNoteOnKey(elemDesc[\midiChan], elemDesc[\midiNum]) },
			\noteOff, { this.makeNoteOffKey(elemDesc[\midiChan], elemDesc[\midiNum]) },
			\noteOnOff, {
				[
					this.makeNoteOnKey(elemDesc[\midiChan], elemDesc[\midiNum]),
					this.makeNoteOffKey(elemDesc[\midiChan], elemDesc[\midiNum])
				]
			},
			\cc, { this.makeCCKey(elemDesc[\midiChan], elemDesc[\midiNum]) },
			\touch, { this.makeTouchKey(elemDesc[\midiChan]) },
			\polyTouch, { this.makePolyTouchKey(elemDesc[\midiChan],elemDesc[\midiNum]) },
			\bend, { this.makeBendKey(elemDesc[\midiChan]) },
			\program, { this.makeProgramKey(elemDesc[\midiChan]) }
		);
		^hashes
	}

		// utilities for fast lookup - in class and instance
		// as class methods so we can do it without an instance
	*makeCCKey { |chan, cc| ^("c_%_%".format(chan, cc)).asSymbol }
	*ccKeyToChanCtl { |ccKey| ^ccKey.asString.drop(2).split($_).asInteger }
	*makeNoteOnKey { |chan, note| ^("non_%_%".format(chan, note)).asSymbol }
	*makeNoteOffKey { |chan, note| ^("nof_%_%".format(chan, note)).asSymbol }
	*makePolyTouchKey { |chan, note| ^("pt_%_%".format(chan, note)).asSymbol }

    *noteKeyToChanNote { |noteKey| ^noteKey.asString.drop(2).split($_).asInteger }

	*makeTouchKey { |chan| ^("t_%".format(chan)).asSymbol }
	*makeBendKey { |chan| ^("b_%".format(chan)).asSymbol }
	*makeProgramKey { |chan| ^("p_%".format(chan)).asSymbol }

	// and as instance methods so we do not need to ask this.class
	makeCCKey { |chan, cc| ^("c_%_%".format(chan, cc)).asSymbol }
	ccKeyToChanCtl { |ccKey| ^ccKey.asString.drop(2).split($_).asInteger }
	makeNoteOnKey { |chan, note| ^("non_%_%".format(chan, note)).asSymbol }
	makeNoteOffKey { |chan, note| ^("nof_%_%".format(chan, note)).asSymbol }
	makePolyTouchKey { |chan, note| ^("pt_%_%".format(chan, note)).asSymbol }

	noteKeyToChanNote { |noteKey| ^noteKey.asString.drop(2).split($_).asInteger }

	makeTouchKey { |chan| ^("t_%".format(chan)).asSymbol }
	makeBendKey { |chan| ^("b_%".format(chan)).asSymbol }
	makeProgramKey { |chan| ^("p_%".format(chan)).asSymbol }

	// was 'plumbing'
	prepareLookupDicts {
		var elementsDict = mktl.elementsDict;
		midiKeyToElemDict = ();

		if (elementsDict.isNil) {
			warn("% has no elementsDict?".format(mktl));
			^this
		};

		elementsDict.do { |elem|
			var elemDesc = elem.elementDescription;
			var midiKey = this.makeHashKey( elemDesc, elem );

			// set the inputs only; outputs can use elemDesc directly
			if ( [nil, \in, \inout].includes(elemDesc[\ioType])) {
				// element has specific description for the input
				midiKey.do { |key|
					midiKeyToElemDict.put(*[key, elem]);
				};
			};
		}
	}


	////////// make the responding MIDIFuncs \\\\\\\
	// only make the ones that are needed once,
	// and activate/deactivate them
	makeCC {
		var typeKey = \cc;
		"make % func\n".postf(typeKey);
		responders.put(typeKey,
			MIDIFunc.cc({ |value, num, chan, src|
				var hash = this.makeCCKey(chan, num);
				var el = midiKeyToElemDict[hash];

				// // do global actions first
				// midiRawAction.value(\control, src, chan, num, value);
				// global[typeKey].value(chan, num, value);

				if (el.notNil) {
					el.deviceValueAction_(value, false);
					if(traceRunning) {
						"% - % > % | type: cc, ccValue:%, ccNum:%,  chan:%, src:%"
						.format(this.name, el.name, el.value.asStringPrec(3), value, num, chan, src).postln
					};
				} {
					if (traceRunning) {
						"MIDIMKtl( % ) : cc element found for chan %, ccnum % !\n"
						" - add it to the description file, e.g.: "
						"\\<name>: (\\midiMsgType: \\cc, \\type: \\button, \\midiChan: %,"
						"\\midiNum: %, \\spec: \\midiBut, \\mode: \\push).\n\n"
						.postf(name, chan, num, chan, num);
					};
				}

			}, srcID: srcID).permanent_(true);
		);
	}

	makeNoteOn {
		var typeKey = \noteOn;
		//"make % func\n".postf(typeKey);
		responders.put(typeKey,
			MIDIFunc.noteOn({ |vel, note, chan, src|
				// look for per-key functions
				var hash = this.makeNoteOnKey(chan, note);
				var el = midiKeyToElemDict[hash];

					// do global actions first
				midiRawAction.value(\noteOn, src, chan, note, vel);
				global[typeKey].value(chan, note, vel);

				if (el.notNil) {
					el.deviceValueAction_(vel);
					if(traceRunning) {
						"% - % > % | type: noteOn, vel:%, midiNote:%,  chan:%, src:%"
						.format(this.name, el.name, el.value.asStringPrec(3),
						vel, note, chan, src).postln
					};
				}{
					if (traceRunning) {
						"MIDIMKtl( % ) : noteOn element found for chan %, note % !\n"
						" - add it to the description file, e.g.: "
						"\\<name>: (\\midiMsgType: \\noteOn, \\type: \\pianoKey or \\button,"
						"\\midiChan: %, \\midiNum: %, \\spec: \\midiVel).\n\n"
						.postf(name, chan, note, chan, note);
					};
				}

			}, srcID: srcID).permanent_(true);
		);
	}

	makeNoteOff {
		var typeKey = \noteOff;
		//"make % func\n".postf(typeKey);
		responders.put(typeKey,
			MIDIFunc.noteOff({ |vel, note, chan, src|
				// look for per-key functions
				var hash = this.makeNoteOffKey(chan, note);
				var el = midiKeyToElemDict[hash];

					// do global actions first
				midiRawAction.value(\noteOff, src, chan, note, vel);
				global[typeKey].value(chan, note, vel);
				if (el.notNil) {
					el.deviceValueAction_(vel);
					if(traceRunning) {
						"% - % > % | type: noteOff, vel:%, midiNote:%,  chan:%, src:%"
						.format(this.name, el.name, el.value.asStringPrec(3), vel, note, chan, src).postln
					};
				} {
					if (traceRunning) {
						"MIDIMKtl( % ) : noteOff element found for chan %, note % !\n"
						" - add it to the description file, e.g.: "
						"\\<name>: (\\midiMsgType: \\noteOff, \\type: \\pianoKey or \\button, \\midiChan: %,"
						"\\midiNum: %, \\spec: \\midiVel).\n\n"
						.postf(name, chan, note, chan, note);
					};
				};


			}, srcID: srcID).permanent_(true);
		);
	}

	// the channel-based ones
	makeTouch {
		var typeKey = \touch;
		var touchElems = mktl.elementsDict.select { |el|
			el.elementDescription[\msgType] == typeKey;
		};
		var touchChans = touchElems.collect { |el|
			el.elementDescription[\midiChan];
		};

		"touchChans: % touchElems: %\n".postf(touchChans, touchElems);

		// var info = MIDIAnalysis.checkForMultiple( mktl.deviceDescriptionArray, typeKey, \midiChan);
		// var chan = info[\midiChan];
		// var listenChan =if (chan.isKindOf(SimpleNumber)) { chan };
		//
		// "make % func\n".postf(typeKey);
		// // do global actions first
		// midiRawAction.value(\touch, src, chan, value);
		// global[typeKey].value(chan, value);
		//
		// responders.put(typeKey,
		// 	MIDIFunc.touch({ |value, chan, src|
		// 		// look for per-key functions
		// 		var hash = this.makeTouchKey(chan);
		// 		var el = midiKeyToElemDict[hash];
		//
		// 		if (el.notNil) {
		// 			el.deviceValueAction_(value);
		// 			if(traceRunning) {
		// 				"% - % > % | type: touch, midiNum:%, chan:%, src:%"
		// 				.format(this.name, el.name, el.value.asStringPrec(3), value, chan, src).postln
		// 			}
		// 		}{
		// 			if (traceRunning) {
		// 				"MIDIMKtl( % ) : touch element found for chan % !\n"
		// 				" - add it to the description file, e.g.: "
		// 				"\\<name>: (\\midiMsgType: \\touch, \\type: \\chantouch', \\midiChan: %,"
		// 				"\\spec: \\midiTouch).\n\n"
		// 				.postf(name, chan, chan);
		// 			};
		// 		};
		//
		//
		// }, chan: listenChan, srcID: srcID).permanent_(true);
	// );
	}

	// not tested yet, no polytouch keyboard
	makePolyTouch {
/*		//"makePolytouch".postln;
		var typeKey = \polyTouch; //decide on polyTouch vs polytouch
		//"make % func\n".postf(typeKey);
		responders.put(typeKey,
			MIDIFunc.polytouch({ |vel, note, chan, src|
				// look for per-key functions
				var hash = this.makePolyTouchKey(chan, note);
				var el = midiKeyToElemDict[hash];

					// do global actions first
				midiRawAction.value(\polyTouch, src, chan, note, vel);
				global[typeKey].value(chan, note, vel);

				if (el.notNil) {
					el.deviceValueAction_(vel);
					if(traceRunning) {
						"% - % > % | type: polyTouch, vel:%, midiNote:%,  chan:%, src:%"
						.format(this.name, el.name, el.value.asStringPrec(3), vel, note, chan, src).postln
					};
				}{
					if (traceRunning) {
						"MIDIMKtl( % ) : polyTouch element found for chan %, note % !\n"
						" - add it to the description file, e.g.: "
						"\\<name>: (\\midiMsgType: \\polyTouch, \\type: \\keytouch, \\midiChan: %,"
						"\\midiNum: %, \\spec: \\midiVel).\n\n"
						.postf(name, chan, note, chan, note);
					};
				}

			}, srcID: srcID).permanent_(true);
		);
*/
	}

	// for the simpler chan based messages, collect chans
	findChans { |typeKey|
		var myElems = mktl.elementsDict.select { |el|
			el.elementDescription[\midiMsgType] == typeKey;
		};
		var myChans = myElems.collect { |el|
			el.elementDescription[\midiChan];
		}.asArray.sort;
		^myChans
	}

	// should work, can't test now.
	makeBend {

		var typeKey = \bend;
		var myChans = this.findChans(typeKey);

		"make % func for chan(s) %\n ".postf(typeKey, myChans);

		responders.put(typeKey,
			MIDIFunc.bend({ |value, chan, src|
				// look for per-key functions
				var hash = this.makeBendKey(chan);
				var el = midiKeyToElemDict[hash];

				if (myChans.includes(chan)) {

					// do global actions first
					midiRawAction.value(\bend, src, chan, value);
					global[typeKey].value(chan, value);

					if (el.notNil) {
						el.deviceValueAction_(value);
						if(traceRunning) {
							"% - % > % | type: bend, midiNum:%, chan:%, src:%"
							.format(this.name, el.name, el.value.asStringPrec(3), value, chan, src).postln
						};
					}{
						if (traceRunning) {
							"MIDIMKtl( % ) : bend element found for chan % !\n"
							" - add it to the description file, e.g.: "
							"\\<name>: (\\midiMsgType: \\bend, \\type: ??', \\midiChan: %,"
							"\\spec: \\midiBend).\n\n"
							.postf(name, chan, chan);
						};
					};
				};

			}, srcID: srcID).permanent_(true);
		);
	}

	makeProgram {
/*
		var typeKey = \program;
		var info = MIDIAnalysis.checkForMultiple( mktl.deviceDescriptionArray, typeKey, \midiChan);
		var chan = info[\midiChan];
		var listenChan =if (chan.isKindOf(SimpleNumber)) { chan };

		//"make % func\n".postf(typeKey);

		responders.put(typeKey,
			MIDIFunc.program({ |value, chan, src|
				// look for per-key functions
				var hash = this.makeProgramKey(chan);
				var el = midiKeyToElemDict[hash];

					// do global actions first
				midiRawAction.value(\program, src, chan, value);
				global[typeKey].value(chan, value);

				if (el.notNil) {
					el.deviceValueAction_(value);
					if(traceRunning) {
						"% - % > % | type: program, midiNum:%, chan:%, src:%"
						.format(this.name, el.name, el.value.asStringPrec(3), value, chan, src).postln
					};
				}{
					if (traceRunning) {
						"MIDIMKtl( % ) : program element found for chan % !\n"
						" - add it to the description file, e.g.: "
						"\\<name>: (\\midiMsgType: \\program, \\type: ??', \\midiChan: %,"
						"\\spec: \\midiProgram).\n\n"
						.postf(name, chan, chan);
					};
				};


			}, chan: listenChan, srcID: srcID).permanent_(true);
		);
*/
	}

	cleanupElementsAndCollectives {
		responders.do { |resp|
			// resp.postln;
			resp.free;
		};
		midiKeyToElemDict = nil;
	}

	makeRespFuncs {
		responders = ();
		global = ();

		msgTypes.do { |msgType|
			switch(msgType,
				\cc, { this.makeCC },
				\noteOn, { this.makeNoteOn },
				\noteOff, { this.makeNoteOff },
				\noteOnOff, { this.makeNoteOn.makeNoteOff },
				\touch, { this.makeTouch },
				\polyTouch, { this.makePolyTouch },
				\bend, { this.makeBend },
				\program, { this.makeProgram }
			);
		};
	}


	// only called by MKtl when there is a midiout,
	// so we should not need to check again

	send { |key, val|
		var elem, elemDesc, msgType, chan, num;
		elem = mktl.elementsDict[key];
		if (elem.isNil) {
			if (traceRunning) {
				"MIDIMKtl send: no elem found for %\n".postf(key);
			};
			^this
		};

		elemDesc = elem.elementDescription;

		if (traceRunning) {
			"MIDIMKtl send: ".post;
			if (elemDesc.isNil) {
				"no elemDesc found for %\n".postf(key);
			} {
				elemDesc.postcs;
			};
			^this
		};

		msgType = elemDesc[\midiMsgType];
		// is this the proper output chan/num?
		// where is it in the elemDesc?
		chan = elemDesc[\midiChan];
		num = elemDesc[\midiNum];

		// could do per-element latency here?
		// e.g. for setting lights later than when pressed
		// fork { (elemDesc[\latency] ? 0).wait;

	 	switch(msgType)
			{ \cc } { midiOut.control(chan, num, val ) }
			{ \noteOn } { midiOut.noteOn(chan, num, val ) }
			{ \noteOff } { midiOut.noteOff(chan, num, val ) }
		//	{ \noteOnOff } { midiOut.noteOn(chan, num, val ) }
			{ \touch } { midiOut.touch(chan, val ) }
			{ \polytouch } { midiOut.polytouch(chan, num, val ) }
			{ \bend } { midiOut.bend(chan, val) }
//			{ \note } { x.postln /*TODO: check type for noteOn, noteOff, etc*/ }
			{warn("MIDIMKtlDevice: message type % not recognised".format(msgType))};
		// };

	}

	sendInitialisationMessages {
		mktl.initialisationMessages.do { |it|
			midiOut.performList( it[0], it.copyToEnd(1) );
		}
	}
}
