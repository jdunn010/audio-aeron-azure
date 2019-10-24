# audio-aeron-azure

    mvn clean install

then to just publish voice and receive transcript back:<br> 

    nohup  java -Dmixer.name=Pixel -Daeron.sample.embeddedMediaDriver=true -jar voice-publisher/target/voice-publisher-1.0-SNAPSHOT-jar-with-dependencies.jar > voice_publisher.log &
    
you should be able to pick up the mixer.name from the list obtained by:

    lsusb
    
you just need to pick some identifying substring from the name, don't need to deal with the whole thing
