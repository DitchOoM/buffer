Pod::Spec.new do |spec|
    spec.name                     = 'buffer'
    spec.version                  = '1.0.0-SNAPSHOT'
    spec.homepage                 = 'https://github.com/DitchOoM/buffer'
    spec.source                   = { :http=> ''}
    spec.authors                  = 'Rahul Behera'
    spec.license                  = 'The Apache Software License, Version 2.0'
    spec.summary                  = 'Multiplatform bytebuffer that delegates to native byte[] or ByteBuffer'
    spec.vendored_frameworks      = 'build/cocoapods/framework/buffer.framework'
    spec.libraries                = 'c++'
                
                
                
    spec.pod_target_xcconfig = {
        'KOTLIN_PROJECT_PATH' => ':',
        'PRODUCT_MODULE_NAME' => 'buffer',
    }
                
    spec.script_phases = [
        {
            :name => 'Build buffer',
            :execution_position => :before_compile,
            :shell_path => '/bin/sh',
            :script => <<-SCRIPT
                if [ "YES" = "$COCOAPODS_SKIP_KOTLIN_BUILD" ]; then
                  echo "Skipping Gradle build task invocation due to COCOAPODS_SKIP_KOTLIN_BUILD environment variable set to \"YES\""
                  exit 0
                fi
                set -ev
                REPO_ROOT="$PODS_TARGET_SRCROOT"
                "$REPO_ROOT/gradlew" -p "$REPO_ROOT" $KOTLIN_PROJECT_PATH:syncFramework \
                    -Pkotlin.native.cocoapods.platform=$PLATFORM_NAME \
                    -Pkotlin.native.cocoapods.archs="$ARCHS" \
                    -Pkotlin.native.cocoapods.configuration="$CONFIGURATION"
            SCRIPT
        }
    ]
                
end