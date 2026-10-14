Android UI tests now really retry a failure three times before going red. The old retry rule never retried a Compose test, it only reported a confusing error.
