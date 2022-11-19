def doingExactClone = false
def exactClone = "EXACT CLONE"
def selectedComponents = []
def sourceEnvDeployments = []
static String ENVIRONMENT_NAMES = "dev,test,pre,prd"
def deploymentBranch = "main"



parameters {
    extendedChoice (description: 'Select an application to copy environment configuration', multiSelectDelimiter: ',',
        name: 'APPLICATION_NAME', defaultValue: "", quoteValue: false, saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT',
        value: "${APIGEE_APPLICATION_NAMES}", visibleItemCount: 50)
    extendedChoice (description: 'Select source environment', multiSelectDelimiter: ',',
        name: 'SOURCE_ENVIRONMENT', quoteValue: false, defaultValue: "", saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT',
        value: "${ENVIRONMENT_NAMES}", visibleItemCount: 50)

    extendedChoice (description: 'Select target environment', multiSelectDelimiter: ',',
        name: 'TARGET_ENVIRONMENT', quoteValue: false, defaultValue: "", saveJSONParameterToFile: false, type: 'PT_SINGLE_SELECT',
        value: "${ENVIRONMENT_NAMES}", visibleItemCount: 50)
}

pipeline {
    agent {
         docker { image 'maven:3.8.6-openjdk-11-slim' }
    }

    stages {
        stage("Checkout Pipeline Code") {
            steps {
                checkout scm
            }
        }

        stage('Select Components') {
            def sourceConfig = readJSON file: "envs/test.json"
            def environment = sourceConfig.environment
                    // process release config to get delta
            def baseline  = readJSON file: "envs/baseline.json"
            sourceEnvDeployments = baseline.environment;
            def componentNames = []
            def values = [exactClone]
            values.addAll(sourceEnvDeployments.collect { it.name })
            print "Deployed Items: "
            println values

            timeout(time: 300, unit: 'SECONDS')
            {
                componentNames = input(
                message: 'Please select the components to update on target environment', ok: 'Next',
                parameters: [
                    extendedChoice(
                    defaultValue: 'All',
                    description: '',
                    multiSelectDelimiter: ',',
                    name: 'COMPONENTS',
                    quoteValue: false,
                    saveJSONParameterToFile: false,
                    type: 'PT_CHECKBOX',
                    value: values.join(','),
                    visibleItemCount: 10)]).split(',')
            }
            if (componentNames.contains(exactClone))
            {
                selectedComponents = componentNames.collect { it.name != exactClone}
            }
            else
            {
                componentNames.each { componentName ->
                    selectedComponents << componentName
                }
            }

            print "Selected components to copy: "
            println selectedComponents

        }


        stage("Clean workspace") {
            steps {
                cleanWs cleanWhenAborted: false, cleanWhenFailure: false, cleanWhenNotBuilt: false, cleanWhenUnstable: false
            }
        }
    }
}
