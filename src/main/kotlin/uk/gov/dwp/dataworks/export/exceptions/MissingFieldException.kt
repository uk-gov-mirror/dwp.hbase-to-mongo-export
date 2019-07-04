package uk.gov.dwp.dataworks.export.exceptions

class MissingFieldException(private val id: String, private val field: String):
        Exception("Missing field '$field' in record '$id'.")
