package com.lookout.borderpatrol.security

import com.google.common.net.InternetDomainName
import com.lookout.borderpatrol.BpNotFoundRequest
import com.twitter.finagle.{Service, SimpleFilter}
import com.twitter.finagle.http.{Response, Request}
import com.twitter.util.Future

/**
  * Created by rikesh.chouhan on 7/18/16.
  * Check that the request received contains a host entry [if present] that is present in the
  * validHosts set of names specified to this filter. If not then return an empty Future[Response]
  * or error
  *
  * @param validHosts
  */
case class HostHeaderFilter(validHosts: Set[InternetDomainName]) extends SimpleFilter[Request, Response] {

  lazy val validHostStrings = validHosts.map( validHost => validHost.toString )

  private[this] def checkHostEntry(request: Request): Unit = {
    request.host.foreach( host => {
      val hostNameOnly = filterPort(host).trim
      if (hostNameOnly.length > 0 && !validHostStrings.contains(hostNameOnly)) {
        throw new BpNotFoundRequest(s"Host Header: '${hostNameOnly}' not found")
      }
    }
    )
  }

  /**
    * Strip out the port portion including the semicolon from the provided
    * host entry.
    * @param host string to strip port from
    * @return empty or stripped port string
    */
  def filterPort(host: String): String = {
    if (host.contains(":") && host.indexOf(":") != 0)
      host.substring(0, host.indexOf(":"))
    else if (!host.contains(":"))
      host
    else /* the entry contains a ':' at the beginning of the string */
      ""
  }

  /**
    * Requests get forwarded to service only for host entries that have been assigned to this filter
    */
  def apply(req: Request, service: Service[Request, Response]): Future[Response] = {
    checkHostEntry(req)
    for {
      resp <- service(req)
    } yield resp
  }
}
